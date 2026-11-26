package uk.co.econetconnect

import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWS4Signer
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.http.HttpMethodName
import java.net.URI
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Read-only transport matching the normal-user MQTT protocol used by ecoNET Cloud.
 * No parameter writes are exposed here.
 */
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
        private const val KEEP_ALIVE_SECONDS = 40
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }

    private val clientId = "$mqttUserId-${System.currentTimeMillis()}"
    private val requestTopic = if (serviceRole) "$installationId/$clientId/serviceRequest" else "$installationId/$clientId/installationRequest"
    private val responseTopic = if (serviceRole) "$installationId/$clientId/serviceResponse" else "$installationId/$clientId/installationResponse"
    private val notificationTopic = if (serviceRole) "$installationId/$clientId/installationNotifications" else "$installationId/installationNotifications"
    private val transaction = AtomicInteger(0)
    @Volatile private var lastResponse: JSONObject? = null
    @Volatile private var connected = false
    private var mqtt: MqttAsyncClient? = null

    @Synchronized
    fun connect() {
        check(!connected) { "MQTT client is already connected." }
        val uri = presignedWebSocketUrl(credentials)
        val client = MqttAsyncClient(uri, clientId, MemoryPersistence())
        val options = MqttConnectOptions().apply {
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1
            isCleanSession = true
            keepAliveInterval = 60
            connectionTimeout = 30
            isAutomaticReconnect = true
        }
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                connected = false
            }
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payload = message?.payload?.toString(StandardCharsets.UTF_8) ?: return
                try {
                    val json = JSONObject(payload)
                    if (topic == responseTopic) lastResponse = json
                    else if (topic == notificationTopic) onNotification(json)
                } catch (_: Exception) { /* Ignore malformed broker payloads. */ }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        client.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(token: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                try {
                    client.subscribe(arrayOf(responseTopic, notificationTopic), intArrayOf(0, 0)).waitForCompletion(15000)
                    connected = true
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    latch.countDown()
                }
            }
            override fun onFailure(token: org.eclipse.paho.client.mqttv3.IMqttToken?, error: Throwable?) {
                failure = error
                latch.countDown()
            }
        })
        if (!latch.await(35, TimeUnit.SECONDS)) {
            try { client.disconnectForcibly() } catch (_: Exception) {}
            throw IllegalStateException("Timed out connecting to the ecoNET MQTT broker.")
        }
        failure?.let {
            val mqttError = it as? org.eclipse.paho.client.mqttv3.MqttException
            val reason = mqttError?.reasonCode?.toString() ?: "n/a"
            val detail = it.cause?.message?.takeIf { message -> message.isNotBlank() }
            throw IllegalStateException(
                "ecoNET MQTT connection failed (reason code $reason): ${detail ?: it.message ?: it.javaClass.simpleName}",
                it
            )
        }
        mqtt = client
    }

    /** Performs an independent HTTP WebSocket upgrade probe and returns safe response diagnostics. */
    fun diagnoseWebSocketHandshake(): String {
        val uri = presignedWebSocketUrl(credentials)
        val query = uri.substringAfter("?", "")
        val safeQuery = query.split("&").mapNotNull { part ->
            val key = part.substringBefore("=")
            if (key in setOf("X-Amz-Algorithm", "X-Amz-Date", "X-Amz-Expires", "X-Amz-SignedHeaders")) part else null
        }.joinToString("&")
        val report = StringBuilder()
            .appendLine("AWS IoT endpoint: $ENDPOINT")
            .appendLine("Signing service: $SERVICE")
            .appendLine("Region: $REGION")
            .appendLine("MQTT subprotocol: mqttv3.1")
            .appendLine("Signed query (secrets removed): $safeQuery")
            .appendLine("Temporary session token present: ${credentials.sessionToken.isNotBlank()}")
        val latch = CountDownLatch(1)
        val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(uri)
            .header("Origin", "https://cloud.grant.eu")
            .header("Sec-WebSocket-Protocol", "mqttv3.1")
            .build()
        var outcome = "No WebSocket response received."
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                outcome = "HTTP status: ${response.code}\nUpgrade accepted: yes\nSelected protocol: ${response.header("Sec-WebSocket-Protocol") ?: "not returned"}"
                webSocket.close(1000, "Diagnostic probe complete")
                latch.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = try { response?.body?.string().orEmpty().take(1200) } catch (_: Exception) { "" }
                outcome = buildString {
                    append("Upgrade accepted: no\n")
                    append("Exception: ${t.javaClass.simpleName}: ${t.message}\n")
                    append("HTTP status: ${response?.code ?: "no HTTP response"}\n")
                    response?.headers?.names()?.sorted()?.forEach { name ->
                        if (name.lowercase() in setOf("x-amzn-errortype", "x-amzn-requestid", "x-amz-cf-id", "date", "server", "content-type")) {
                            append("$name: ${response.header(name)}\n")
                        }
                    }
                    if (body.isNotBlank()) append("Response body: $body")
                }
                latch.countDown()
            }
        })
        if (!latch.await(27, TimeUnit.SECONDS)) outcome = "WebSocket diagnostic timed out after 27 seconds."
        client.dispatcher.executorService.shutdown()
        report.appendLine(outcome)
        return report.toString()
    }

    /**
     * Sends one read-only MQTT operation and waits for its matching transaction response.
     */
    @Synchronized
    fun request(operation: JSONObject, timeoutMs: Long = REQUEST_TIMEOUT_MS): JSONObject {
        check(connected) { "MQTT is not connected." }
        val id = transaction.incrementAndGet().toString()
        val envelope = JSONObject().put("transactionId", id).put("operations", org.json.JSONArray().put(operation))
        lastResponse = null
        mqtt!!.publish(requestTopic, MqttMessage(envelope.toString().toByteArray(StandardCharsets.UTF_8)).apply {
            qos = 0
            isRetained = false
        })
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val response = lastResponse
            if (response != null && response.optString("transactionId") == id) {
                val operations = response.optJSONArray("operations")
                    ?: throw IllegalStateException("MQTT response did not contain operations.")
                if (operations.length() == 0) throw IllegalStateException("MQTT response contained no operation result.")
                return operations.getJSONObject(0)
            }
            Thread.sleep(50)
        }
        throw IllegalStateException("ecoNET MQTT request timed out after ${timeoutMs}ms.")
    }

    @Synchronized
    fun disconnect() {
        connected = false
        try { mqtt?.disconnectForcibly() } catch (_: Exception) {}
        try { mqtt?.close() } catch (_: Exception) {}
        mqtt = null
    }

    private fun presignedWebSocketUrl(creds: AWSTemporaryCredentials): String {
        // Use the same AWS SDK SigV4 presigner as the REST client. The official
        // ecoNET web client delegates this to Amplify's AWS signer; hand-built
        // canonical requests are too easy to subtly mismatch.
        val request = DefaultRequest<Any>(SERVICE).apply {
            setEndpoint(URI.create("https://$ENDPOINT"))
            setResourcePath("/mqtt")
            setHttpMethod(HttpMethodName.GET)
        }
        // ecoNET's signer deliberately excludes the IoT session token from the
        // SigV4 canonical query, then appends X-Amz-Security-Token afterwards.
        // The token is still required by AWS, but must not be part of this signature.
        val awsCredentials = BasicAWSCredentials(
            creds.accessKeyId, creds.secretAccessKey
        )
        val signer = AWS4Signer().apply {
            setRegionName(REGION)
            setServiceName(SERVICE)
        }
        signer.presignRequest(request, awsCredentials, Date(System.currentTimeMillis() + 900_000L))
        val query = request.parameters.entries.joinToString("&") { entry ->
            "${encode(entry.key)}=${encode(entry.value)}"
        }
        val token = encode(creds.sessionToken)
        return "wss://$ENDPOINT/mqtt?$query&X-Amz-Security-Token=$token"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun hmac(key: ByteArray, value: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray(StandardCharsets.UTF_8))
        }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
