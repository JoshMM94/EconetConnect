package uk.co.econetconnect

import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWS4Signer
import com.amazonaws.auth.BasicSessionCredentials
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
    @Volatile private var lastResponse: JSONObject? = null
    @Volatile private var socketFailure: String? = null

    @Synchronized
    fun connect() {
        check(!connected) { "MQTT client is already connected." }
        val opened = CountDownLatch(1)
        val request = Request.Builder().url(presignedWebSocketUrl(credentials))
            .header("Origin", "https://cloud.grant.eu")
            .header("Sec-WebSocket-Protocol", "mqttv3.1").build()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                opened.countDown()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMqttBytes(bytes.toByteArray())
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
        waitUntil(10_000L, "Timed out waiting for MQTT CONNACK.") {
            connAckCode != null || connAckError != null || socketFailure != null
        }
        socketFailure?.let { throw IllegalStateException("WebSocket closed before MQTT CONNACK: $it") }
        connAckError?.let { throw IllegalStateException(it) }
        val code = connAckCode ?: throw IllegalStateException("MQTT broker did not return CONNACK.")
        if (code != 0) throw IllegalStateException("MQTT CONNACK rejected the client (return code $code).")

        val subscribeId = nextPacketId()
        val subscribeBody = PacketWriter().short(subscribeId)
            .mqttString(responseTopic).byte(0).mqttString(notificationTopic).byte(0).result()
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

    fun diagnoseWebSocketHandshake(): String {
        val uri = presignedWebSocketUrl(credentials)
        val safeQuery = uri.substringAfter("?", "").split("&").mapNotNull { part ->
            val key = part.substringBefore("=")
            if (key in setOf("X-Amz-Algorithm", "X-Amz-Date", "X-Amz-Expires", "X-Amz-SignedHeaders")) part else null
        }.joinToString("&")
        val latch = CountDownLatch(1)
        var outcome = "No WebSocket response received."
        val probe = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
        probe.newWebSocket(Request.Builder().url(uri).header("Origin", "https://cloud.grant.eu")
            .header("Sec-WebSocket-Protocol", "mqttv3.1").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                outcome = "HTTP ${response.code}; Upgrade accepted: yes; protocol: ${response.header("Sec-WebSocket-Protocol") ?: "not returned"}"
                webSocket.close(1000, "Diagnostic complete"); latch.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                outcome = "Upgrade accepted: no; ${t.javaClass.simpleName}: ${t.message}; HTTP ${response?.code ?: "no response"}"
                latch.countDown()
            }
        })
        if (!latch.await(27, TimeUnit.SECONDS)) outcome = "WebSocket diagnostic timed out."
        probe.dispatcher.executorService.shutdown()
        return "AWS IoT endpoint: $ENDPOINT\nSigning service: $SERVICE\nRegion: $REGION\nMQTT subprotocol: mqttv3.1\nSigned query (secrets removed): $safeQuery\nTemporary session token present: ${credentials.sessionToken.isNotBlank()}\n$outcome"
    }

    @Synchronized
    fun request(operation: JSONObject, timeoutMs: Long = REQUEST_TIMEOUT_MS): JSONObject {
        check(connected) { "MQTT is not connected." }
        val id = transaction.incrementAndGet().toString()
        val envelope = JSONObject().put("transactionId", id)
            .put("operations", org.json.JSONArray().put(operation))
        lastResponse = null
        val payload = envelope.toString().toByteArray(StandardCharsets.UTF_8)
        val body = PacketWriter().mqttString(requestTopic).bytes(payload).result()
        if (socket?.send(ByteString.of(*mqttPacket(0x30, body))) != true) {
            throw IllegalStateException("Could not publish MQTT request.")
        }
        waitUntil(timeoutMs, "ecoNET MQTT request timed out after ${timeoutMs}ms.") {
            lastResponse?.optString("transactionId") == id || socketFailure != null
        }
        socketFailure?.let { throw IllegalStateException("MQTT connection lost: $it") }
        val response = lastResponse ?: throw IllegalStateException("No matching MQTT response was received.")
        if (response.optString("transactionId") != id) throw IllegalStateException("Received an unrelated MQTT response.")
        val operations = response.optJSONArray("operations")
            ?: throw IllegalStateException("MQTT response did not contain operations.")
        if (operations.length() == 0) throw IllegalStateException("MQTT response contained no operation result.")
        val first = operations.opt(0)
        if (first is JSONObject) return first
        if (first is String) {
            throw IllegalStateException("ecoNET MQTT operation returned a string error instead of an object: " + first)
        }
        throw IllegalStateException("ecoNET MQTT operation result had unexpected type: " +
            (first?.javaClass?.simpleName ?: "null") + "; raw response: " + response.toString())
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
        val request = DefaultRequest<Any>(SERVICE).apply {
            setEndpoint(URI.create("https://$ENDPOINT"))
            setResourcePath("/mqtt")
            setHttpMethod(HttpMethodName.GET)
        }
        val signer = AWS4Signer().apply { setRegionName(REGION); setServiceName(SERVICE) }
        // The temporary session token must be part of the SigV4 presigned query
        // BEFORE the signature is calculated. Appending it afterwards invalidates
        // the canonical request used by AWS IoT.
        signer.presignRequest(request, BasicSessionCredentials(
            creds.accessKeyId, creds.secretAccessKey, creds.sessionToken
        ), Date(System.currentTimeMillis() + 900_000L))
        val query = request.parameters.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return "wss://$ENDPOINT/mqtt?$query"
    }

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
