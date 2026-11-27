package uk.co.econetconnect

import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWS4Signer
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.http.HttpMethodName
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Minimal MQTT 3.1 transport over the same OkHttp WebSocket used by the successful handshake probe. */
class EcoNetMqttClient(
    private val installationId: String,
    private val mqttUserId: String,
    private val credentials: AWSTemporaryCredentials,
    private val serviceRole: Boolean = false,
    private val onNotification: (JSONObject) -> Unit = {}
) {
    companion object {
        private const val REGION = "eu-central-1"
        private const val SERVICE = "iotdevicegateway"
        private const val ENDPOINT = "a24t7r3f2r1nrr-ats.iot.eu-central-1.amazonaws.com"
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }

    private val clientId = "$mqttUserId-${System.currentTimeMillis()}"
    private val requestTopic = if (serviceRole) "$installationId/$clientId/serviceRequest" else "$installationId/$clientId/installationRequest"
    private val responseTopic = if (serviceRole) "$installationId/$clientId/serviceResponse" else "$installationId/$clientId/installationResponse"
    private val notificationTopic = if (serviceRole) "$installationId/$clientId/installationNotifications" else "$installationId/installationNotifications"
    private val transaction = AtomicInteger(0)
    private val packetIds = AtomicInteger(1)
    private val monitor = Object()
    private val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val keepAlive = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var connAckCode: Int? = null
    @Volatile private var connAckError: String? = null
    @Volatile private var subAckId: Int? = null
    @Volatile private var subAckCodes: IntArray? = null
    @Volatile private var pubAckId: Int? = null
    @Volatile private var lastResponse: JSONObject? = null
    @Volatile private var socketFailure: String? = null
    @Volatile private var lastInboundFrame: String? = null

    @Synchronized
    fun connect() {
        check(!connected) { "MQTT client is already connected." }
        val opened = CountDownLatch(1)
        val request = Request.Builder().url(presignedWebSocketUrl(credentials))
            .header("Origin", "https://econetcloud.eu")
            .header("Sec-WebSocket-Protocol", "mqttv3.1").build()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                opened.countDown()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                lastInboundFrame = "binary length=${raw.size}, hex=" + raw.take(32).joinToString(" ") { "%02X".format(it) }
                handleMqttBytes(raw)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundFrame = "text length=${text.length}, preview=" + text.take(160)
                synchronized(monitor) { monitor.notifyAll() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                socketFailure = "WebSocket closing ($code): $reason"
                synchronized(monitor) { monitor.notifyAll() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = try { response?.body?.string().orEmpty().take(500) } catch (_: Exception) { "" }
                socketFailure = "${t.javaClass.simpleName}: ${t.message}" +
                    (response?.let { "; HTTP ${it.code}" } ?: "") + if (body.isNotBlank()) "; $body" else ""
                synchronized(monitor) { monitor.notifyAll() }
                opened.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                socketFailure = "WebSocket closed ($code): $reason"
                synchronized(monitor) { monitor.notifyAll() }
            }
        })
        if (!opened.await(30, TimeUnit.SECONDS)) throw IllegalStateException("Timed out opening AWS IoT WebSocket.")
        socketFailure?.let { throw IllegalStateException("AWS IoT WebSocket failed: $it") }

        // MQTT 3.1 CONNECT: protocol name MQIsdp, level 3, clean-session flag.
        val connectBody = PacketWriter().mqttString("MQIsdp").byte(3).byte(0x02).short(60)
            .mqttString(clientId).result()
        if (socket?.send(ByteString.of(*mqttPacket(0x10, connectBody))) != true) {
            throw IllegalStateException("Could not send MQTT CONNECT.")
        }
        waitUntil(10_000L, "Timed out waiting for MQTT CONNACK. Last inbound frame: ${lastInboundFrame ?: "(none)"}") {
            connAckCode != null || connAckError != null || socketFailure != null
        }
        socketFailure?.let { throw IllegalStateException("WebSocket closed before MQTT CONNACK: $it") }
        connAckError?.let { throw IllegalStateException(it) }
        val code = connAckCode ?: throw IllegalStateException("MQTT broker did not return CONNACK.")
        if (code != 0) throw IllegalStateException("MQTT CONNACK rejected the client (return code $code).")

        val subscribeId = nextPacketId()
        val subscribeBody = PacketWriter().short(subscribeId)
            .mqttString(responseTopic).byte(1).mqttString(notificationTopic).byte(1).result()
        if (socket?.send(ByteString.of(*mqttPacket(0x82, subscribeBody))) != true) {
            throw IllegalStateException("Could not send MQTT SUBSCRIBE.")
        }
        waitUntil(10_000L, "Timed out waiting for MQTT SUBACK.") {
            subAckId == subscribeId || socketFailure != null
        }
        socketFailure?.let { throw IllegalStateException("MQTT connection lost during SUBSCRIBE: $it") }
        val codes = subAckCodes ?: throw IllegalStateException("Broker did not acknowledge MQTT subscriptions.")
        if (codes.any { it == 0x80 || it > 2 }) throw IllegalStateException("MQTT subscription rejected: ${codes.joinToString()}")
        connected = true
        keepAlive.scheduleAtFixedRate({
            if (connected) socket?.send(ByteString.of(0xC0.toByte(), 0x00.toByte()))
        }, 40, 40, TimeUnit.SECONDS)
    }

    fun diagnoseWebSocketHandshake(probeLabel: String, sendOrigin: Boolean = true): String {
        val uri = presignedWebSocketUrl(credentials)
        val queryParts = uri.substringAfter("?", "").split("&").filter { it.contains("=") }
        val queryNames = queryParts.map { it.substringBefore("=") }.sorted().distinct()
        val safeQuery = queryParts.mapNotNull { part ->
            val key = part.substringBefore("=")
            if (key in setOf("X-Amz-Algorithm", "X-Amz-Date", "X-Amz-Expires", "X-Amz-SignedHeaders")) part else null
        }.joinToString("&")
        val credentialScope = queryParts.firstOrNull { it.startsWith("X-Amz-Credential=") }
            ?.substringAfter("=")?.substringAfter("%2F", "")?.replace("%2F", "/")
            ?.substringAfter("/", "")
            ?: "(not present)"
        val latch = CountDownLatch(1)
        var outcome = "No WebSocket response received."
        val probe = OkHttpClient.Builder().callTimeout(27, TimeUnit.SECONDS).build()
        val builder = Request.Builder().url(uri).header("Sec-WebSocket-Protocol", "mqttv3.1")
        if (sendOrigin) builder.header("Origin", "https://econetcloud.eu")
        probe.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val headers = listOf("Date", "Server", "x-amzn-RequestId", "x-amzn-ErrorType",
                    "x-amz-cf-id", "content-type", "content-length")
                    .mapNotNull { name -> response.header(name)?.let { "$name=$it" } }
                    .joinToString("; ").ifBlank { "(none returned)" }
                outcome = "HTTP ${response.code}; Upgrade accepted: yes; protocol: ${response.header("Sec-WebSocket-Protocol") ?: "not returned"}; response headers: $headers"
                webSocket.close(1000, "Diagnostic complete")
                latch.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val headers = listOf("Date", "Server", "x-amzn-RequestId", "x-amzn-ErrorType",
                    "x-amz-cf-id", "content-type", "content-length")
                    .mapNotNull { name -> response?.header(name)?.let { "$name=$it" } }
                    .joinToString("; ").ifBlank { "(none returned)" }
                val body = try { response?.body?.string().orEmpty().take(1200) } catch (_: Exception) { "" }
                val safeBody = body.replace(Regex("""(?i)(X-Amz-Signature|X-Amz-Security-Token|Authorization)[=:][^&\\s"}]+"""), "\\$1=[REDACTED]")
                outcome = "Upgrade accepted: no; ${t.javaClass.simpleName}: ${t.message}; HTTP ${response?.code ?: "no response"}; response headers: $headers; response body: ${safeBody.ifBlank { "(empty)" }}"
                latch.countDown()
            }
        })
        if (!latch.await(29, TimeUnit.SECONDS)) outcome = "WebSocket diagnostic timed out."
        probe.dispatcher.executorService.shutdown()
        return "Probe: $probeLabel\nClient ID suffix: ${clientId.takeLast(8)} (length ${clientId.length})\nOrigin header sent: $sendOrigin\nAWS IoT endpoint: $ENDPOINT\nSigning service: $SERVICE\nRegion: $REGION\nCredential scope: $credentialScope\nMQTT subprotocol: mqttv3.1\nSigned query fields: ${queryNames.joinToString()}\nSigned query (secrets removed): $safeQuery\nTemporary session token present: ${credentials.sessionToken.isNotBlank()} (length ${credentials.sessionToken.length})\n$outcome"
    }

    @Synchronized
    fun requestEnvelope(operation: JSONObject, timeoutMs: Long = REQUEST_TIMEOUT_MS): JSONObject {
        check(connected) { "MQTT is not connected." }
        val id = transaction.incrementAndGet().toString()
        val envelope = JSONObject().put("transactionId", id)
            .put("operations", org.json.JSONArray().put(operation))
        lastResponse = null
        val payload = envelope.toString().toByteArray(StandardCharsets.UTF_8)
        val publishId = nextPacketId()
        pubAckId = null
        val body = PacketWriter().mqttString(requestTopic).short(publishId).bytes(payload).result()
        if (socket?.send(ByteString.of(*mqttPacket(0x32, body))) != true) {
            throw IllegalStateException("Could not publish MQTT request.")
        }
        waitUntil(timeoutMs, "Timed out waiting for MQTT PUBACK for packet $publishId.") {
            pubAckId == publishId || socketFailure != null
        }
        socketFailure?.let { throw IllegalStateException("MQTT connection lost before PUBACK: $it") }
        waitUntil(timeoutMs, "ecoNET MQTT request timed out after ${timeoutMs}ms.") {
            lastResponse?.optString("transactionId") == id || socketFailure != null
        }
        socketFailure?.let { throw IllegalStateException("MQTT connection lost: $it") }
        val response = lastResponse ?: throw IllegalStateException("No matching MQTT response was received.")
        if (response.optString("transactionId") != id) throw IllegalStateException("Received an unrelated MQTT response.")
        return response
    }

    @Synchronized
    fun request(operation: JSONObject, timeoutMs: Long = REQUEST_TIMEOUT_MS): JSONObject {
        val response = requestEnvelope(operation, timeoutMs)
        val operations = response.optJSONArray("operations") ?: return response
        if (operations.length() == 0) return response
        val first = operations.opt(0)
        if (first is JSONObject) return first
        if (first is String) throw IllegalStateException("ecoNET MQTT operation returned a string error: $first")
        throw IllegalStateException("Unexpected MQTT operation result: ${first?.javaClass?.simpleName ?: "null"}; raw response: $response")
    }
    @Synchronized
    fun disconnect() {
        connected = false
        try { socket?.send(ByteString.of(0xE0.toByte(), 0x00.toByte())) } catch (_: Exception) {}
        try { socket?.close(1000, "Client disconnect") } catch (_: Exception) {}
        socket = null
        keepAlive.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun handleMqttBytes(packet: ByteArray) {
        if (packet.size < 2) return
        val header = packet[0].toInt() and 0xFF
        var index = 1
        var remaining = 0
        var multiplier = 1
        var digit: Int
        do {
            if (index >= packet.size) return
            digit = packet[index++].toInt() and 0xFF
            remaining += (digit and 0x7F) * multiplier
            multiplier *= 128
        } while ((digit and 0x80) != 0 && multiplier <= 128 * 128 * 128 * 128)
        if (index + remaining > packet.size) return
        val end = index + remaining
        when (header ushr 4) {
            2 -> if (remaining >= 2) {
                connAckCode = packet[index + 1].toInt() and 0xFF
                if (connAckCode != 0) connAckError = "MQTT CONNACK rejected connection (return code $connAckCode)."
                synchronized(monitor) { monitor.notifyAll() }
            }
            4 -> if (remaining >= 2) {
                pubAckId = ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
                synchronized(monitor) { monitor.notifyAll() }
            }
            9 -> if (remaining >= 3) {
                subAckId = ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
                subAckCodes = (index + 2 until end).map { packet[it].toInt() and 0xFF }.toIntArray()
                synchronized(monitor) { monitor.notifyAll() }
            }
            3 -> if (remaining >= 2) {
                val topicLength = ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
                var payloadStart = index + 2 + topicLength
                if (payloadStart > end) return
                val topic = String(packet, index + 2, topicLength, StandardCharsets.UTF_8)
                val qos = (header ushr 1) and 0x03
                if (qos > 0) payloadStart += 2
                if (payloadStart > end) return
                val text = String(packet, payloadStart, end - payloadStart, StandardCharsets.UTF_8)
                try {
                    val json = JSONObject(text)
                    when (topic) {
                        responseTopic -> { lastResponse = json; synchronized(monitor) { monitor.notifyAll() } }
                        notificationTopic -> onNotification(json)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun waitUntil(timeoutMs: Long, timeoutMessage: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(monitor) {
            while (!condition()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw IllegalStateException(timeoutMessage)
                monitor.wait(remaining)
            }
        }
    }

    private fun nextPacketId(): Int = ((packetIds.getAndIncrement() - 1) % 65535) + 1

    private fun mqttPacket(header: Int, body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(body.size + 5)
        out.write(header)
        var length = body.size
        do {
            var digit = length % 128
            length /= 128
            if (length > 0) digit = digit or 0x80
            out.write(digit)
        } while (length > 0)
        out.write(body)
        return out.toByteArray()
    }

    private fun presignedWebSocketUrl(creds: AWSTemporaryCredentials): String {
        // Port of econet-api-client/src/iot/sigv4.ts. Its IoT URL intentionally
        // omits X-Amz-Expires and appends the temporary session token unsigned.
        val now = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC)
        val datetime = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(now)
        val date = datetime.substring(0, 8)
        val scope = "$date/$REGION/$SERVICE/aws4_request"
        val params = sortedMapOf(
            "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
            "X-Amz-Credential" to "${creds.accessKeyId}/$scope",
            "X-Amz-Date" to datetime,
            "X-Amz-SignedHeaders" to "host"
        )
        val canonicalQuery = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val canonicalRequest = "GET\n/mqtt\n$canonicalQuery\nhost:$ENDPOINT\n\nhost\n${sha256Hex(ByteArray(0))}"
        val stringToSign = "AWS4-HMAC-SHA256\n$datetime\n$scope\n${sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))}"
        val kDate = hmacSha256(("AWS4" + creds.secretAccessKey).toByteArray(StandardCharsets.UTF_8), date)
        val kRegion = hmacSha256(kDate, REGION)
        val kService = hmacSha256(kRegion, SERVICE)
        val kSigning = hmacSha256(kService, "aws4_request")
        val signature = hmacSha256(kSigning, stringToSign).joinToString("") { "%02x".format(it) }
        return "wss://$ENDPOINT/mqtt?$canonicalQuery&X-Amz-Signature=$signature&X-Amz-Security-Token=${encode(creds.sessionToken)}"
    }

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha256Hex(value: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")

    private class PacketWriter {
        private val out = java.io.ByteArrayOutputStream()
        fun byte(value: Int) = apply { out.write(value and 0xFF) }
        fun short(value: Int) = apply { out.write((value ushr 8) and 0xFF); out.write(value and 0xFF) }
        fun bytes(value: ByteArray) = apply { out.write(value) }
        fun mqttString(value: String) = apply {
            val b = value.toByteArray(StandardCharsets.UTF_8); short(b.size); out.write(b)
        }
        fun result(): ByteArray = out.toByteArray()
    }
}
