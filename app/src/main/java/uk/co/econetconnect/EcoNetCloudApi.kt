package uk.co.econetconnect

import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.core.Amplify
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class EcoNetInstallation(
    val id: String,
    val name: String,
    val factoryNumber: String,
    val address: String,
    val connected: Boolean,
    val hasAlarms: Boolean,
    val softwareVersion: String
)

object EcoNetCloudApi {
    private const val API_HOST = "6tx17a8ijh.execute-api.eu-central-1.amazonaws.com"
    private const val API_PATH = "/prod/v1/get-installation-list"
    private const val API_URL = "https://$API_HOST$API_PATH"
    private const val AWS_REGION = "eu-central-1"
    private const val AWS_SERVICE = "execute-api"
    private const val APP_ID = "114"

    fun fetchInstallations(
        onSuccess: (List<EcoNetInstallation>) -> Unit,
        onError: (String) -> Unit
    ) {
        Amplify.Auth.fetchAuthSession(
            { authSession ->
                try {
                    val session = authSession as? AWSCognitoAuthSession
                        ?: throw IllegalStateException("Cognito session was not available.")
                    if (!session.isSignedIn) {
                        throw IllegalStateException("The ecoNET Cloud session is no longer signed in. Please sign in again.")
                    }
                    val credentialsResult = session.awsCredentialsResult
                    val credentials = credentialsResult.value as? AWSTemporaryCredentials
                        ?: throw IllegalStateException("AWS temporary credentials could not be obtained. Check the Cognito Identity Pool configuration.")

                    Thread {
                        try {
                            val installations = requestInstallationList(credentials)
                            Handler(Looper.getMainLooper()).post { onSuccess(installations) }
                        } catch (error: Exception) {
                            val message = error.message ?: "Could not retrieve installations from ecoNET Cloud."
                            Handler(Looper.getMainLooper()).post { onError(message) }
                        }
                    }.start()
                } catch (error: Exception) {
                    onError(error.message ?: "Could not prepare the authenticated ecoNET Cloud request.")
                }
            },
            { error ->
                onError(error.localizedMessage ?: "Could not retrieve the authenticated AWS session.")
            }
        )
    }

    private fun requestInstallationList(credentials: AWSTemporaryCredentials): List<EcoNetInstallation> {
        val now = Instant.now()
        val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC).format(now)
        val dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC).format(now)

        val payloadHash = sha256Hex("")
        // Header order must match the lexicographically sorted SigV4 SignedHeaders list below.
        val canonicalHeaders = "appid:$APP_ID\nhost:$API_HOST\nx-amz-date:$amzDate\nx-amz-security-token:${credentials.sessionToken.trim()}\n"
        val signedHeaders = "appid;host;x-amz-date;x-amz-security-token"
        val canonicalRequest = "GET\n$API_PATH\n\n$canonicalHeaders$signedHeaders\n$payloadHash"
        val credentialScope = "$dateStamp/$AWS_REGION/$AWS_SERVICE/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest)}"

        val dateKey = hmac(("AWS4" + credentials.secretAccessKey).toByteArray(StandardCharsets.UTF_8), dateStamp)
        val regionKey = hmac(dateKey, AWS_REGION)
        val serviceKey = hmac(regionKey, AWS_SERVICE)
        val signingKey = hmac(serviceKey, "aws4_request")
        val signature = hmac(signingKey, stringToSign).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val authorization = "AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("appid", APP_ID)
            setRequestProperty("x-amz-date", amzDate)
            setRequestProperty("x-amz-security-token", credentials.sessionToken)
            setRequestProperty("Authorization", authorization)
        }

        try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                val detail = try {
                    val json = JSONObject(responseText)
                    json.optString("message").ifBlank { json.optString("error") }
                } catch (_: Exception) {
                    ""
                }
                throw IllegalStateException(
                    if (detail.isNotBlank()) "ecoNET Cloud returned HTTP $statusCode: $detail"
                    else "ecoNET Cloud returned HTTP $statusCode. The request was signed, but the service did not accept it."
                )
            }

            val array = JSONArray(responseText)
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
                    add(
                        EcoNetInstallation(
                            id = id,
                            name = item.optString("customName").takeIf { it.isNotBlank() && it != "null" }
                                ?: item.optString("name").takeIf { it.isNotBlank() && it != "null" }
                                ?: "Installation",
                            factoryNumber = item.optString("factoryNumber").takeIf { it.isNotBlank() && it != "null" } ?: "—",
                            address = item.optString("fullAddress").takeIf { it.isNotBlank() && it != "null" } ?: "",
                            connected = item.optBoolean("isConnected", false),
                            hasAlarms = item.optBoolean("hasAlarms", false),
                            softwareVersion = item.optString("softVersion").takeIf { it.isNotBlank() && it != "null" } ?: ""
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }
}
