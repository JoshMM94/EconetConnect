package uk.co.econetconnect

import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.core.Amplify
import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWS4Signer
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.http.HttpMethodName
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class EcoNetInstallation(
    val id: String,
    val name: String,
    val factoryNumber: String,
    val address: String,
    val connected: Boolean,
    val hasAlarms: Boolean,
    val softwareVersion: String
)


data class EcoNetComponent(
    val name: String, val serialNumber: String, val softwareVersion: String,
    val hardwareVersion: String, val protocol: String, val producerName: String = "",
    val componentType: String = "", val componentTypeId: Int = 0, val producerCode: Int = 0
)
data class EcoNetInstallationDetails(
    val name: String, val serialNumber: String, val address: String,
    val connected: Boolean, val hasAlarms: Boolean, val softwareVersion: String,
    val hardwareVersion: String, val components: List<EcoNetComponent>,
    val invalidComponents: List<String>,
    val registrationStatus: String = "Not started"
)

data class EcoNetControllerProfile(
    val componentName: String,
    val producerName: String,
    val hardwareVersion: String,
    val softwareVersion: String,
    val profile: JSONObject,
    val translations: JSONObject
)

object EcoNetCloudApi {
    private const val API_HOST = "6tx17a8ijh.execute-api.eu-central-1.amazonaws.com"
    private const val API_PATH = "/prod/v1/get-installation-list"
    private const val API_URL = "https://$API_HOST$API_PATH"
    private const val API_ENDPOINT = "https://$API_HOST"
    private const val AWS_REGION = "eu-central-1"
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


    fun fetchInstallationDetails(
        installationId: String,
        onSuccess: (EcoNetInstallationDetails) -> Unit,
        onError: (String) -> Unit
    ) {
        Amplify.Auth.fetchAuthSession(
            { authSession ->
                try {
                    val session = authSession as? AWSCognitoAuthSession
                        ?: throw IllegalStateException("Cognito session was not available.")
                    if (!session.isSignedIn) throw IllegalStateException("Please sign in again.")
                    val credentials = session.awsCredentialsResult.value as? AWSTemporaryCredentials
                        ?: throw IllegalStateException("AWS temporary credentials could not be obtained.")
                    Thread {
                        try {
                            val path = "/prod/v1/installation/$installationId/get-details"
                            val request = DefaultRequest<Any>("execute-api").apply {
                                setEndpoint(URI.create(API_ENDPOINT))
                                setResourcePath(path)
                                setHttpMethod(HttpMethodName.GET)
                                addHeader("appid", APP_ID)
                            }
                            val signer = AWS4Signer().apply {
                                setRegionName(AWS_REGION)
                                setServiceName("execute-api")
                            }
                            signer.sign(request, BasicSessionCredentials(
                                credentials.accessKeyId, credentials.secretAccessKey, credentials.sessionToken
                            ))
                            val connection = (URL("https://$API_HOST$path").openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                connectTimeout = 20000
                                readTimeout = 30000
                                setRequestProperty("Accept", "application/json")
                                request.headers.forEach { (name, value) ->
                                    if (!name.equals("host", ignoreCase = true)) setRequestProperty(name, value)
                                }
                            }
                            val code = connection.responseCode
                            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                            connection.disconnect()
                            if (code !in 200..299) {
                                val detail = try { JSONObject(body).optString("message") } catch (_: Exception) { "" }
                                throw IllegalStateException(if (detail.isNotBlank()) "HTTP $code: $detail" else "ecoNET Cloud returned HTTP $code.")
                            }
                            val root = JSONObject(body)
                            val info = root.optJSONObject("installationInfo") ?: JSONObject()
                            val rawComponents = root.optJSONArray("components") ?: JSONArray()
                            val components = buildList {
                                for (i in 0 until rawComponents.length()) {
                                    val item = rawComponents.optJSONObject(i) ?: continue
                                    add(EcoNetComponent(
                                        name = item.optString("customName").takeIf { it.isNotBlank() && it != "null" }
                                            ?: item.optString("componentType").ifBlank { "Component" },
                                        serialNumber = item.optString("componentFn").ifBlank { "—" },
                                        softwareVersion = item.optString("softVersion").ifBlank { "—" },
                                        hardwareVersion = item.optString("hardwareVersion").ifBlank { "—" },
                                        protocol = item.optString("protocol").ifBlank { "—" },
                                        producerName = item.optString("producerName").takeIf { it.isNotBlank() && it != "null" }
                                            ?: item.optString("producer").takeIf { it.isNotBlank() && it != "null" }
                                            ?: item.optString("componentType").takeIf { it.isNotBlank() && it != "null" }
                                            ?: "",
                                        componentType = item.optString("componentType").takeIf { it.isNotBlank() && it != "null" } ?: "",
                                        componentTypeId = item.optInt("componentTypeId", item.optInt("typeId", 0)),
                                        producerCode = item.optInt("producerCode", item.optInt("manufacturerCode", 0))
                                    ))
                                }
                            }
                            val rawInvalid = root.optJSONArray("invalidComponents") ?: JSONArray()
                            val invalid = buildList {
                                for (i in 0 until rawInvalid.length()) {
                                    val item = rawInvalid.optJSONObject(i) ?: continue
                                    add("${item.optString("componentFn", "Unknown component")} (status ${item.optInt("status")})")
                                }
                            }
                            val details = EcoNetInstallationDetails(
                                name = info.optString("customName").takeIf { it.isNotBlank() && it != "null" }
                                    ?: info.optString("name", "Installation"),
                                serialNumber = info.optString("factoryNumber").ifBlank { "—" },
                                address = info.optString("fullAddress"),
                                connected = info.optBoolean("isConnected", false),
                                hasAlarms = info.optBoolean("hasAlarms", false),
                                softwareVersion = info.optString("softVersion").ifBlank { "—" },
                                hardwareVersion = info.optString("hardwareVersion").ifBlank { "—" },
                                components = components, invalidComponents = invalid
                            )
                            Handler(Looper.getMainLooper()).post { onSuccess(details) }
                        } catch (error: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                onError(error.message ?: "Could not retrieve installation details.")
                            }
                        }
                    }.start()
                } catch (error: Exception) {
                    onError(error.message ?: "Could not prepare installation request.")
                }
            },
            { error -> onError(error.localizedMessage ?: "Could not retrieve AWS session.") }
        )
    }


    fun fetchControllerProfiles(
        components: List<EcoNetComponent>,
        onSuccess: (List<EcoNetControllerProfile>) -> Unit,
        onError: (String) -> Unit
    ) {
        Amplify.Auth.fetchAuthSession(
            { authSession ->
                try {
                    val session = authSession as? AWSCognitoAuthSession
                        ?: throw IllegalStateException("Cognito session was not available.")
                    if (!session.isSignedIn) throw IllegalStateException("Please sign in again.")
                    val credentials = session.awsCredentialsResult.value as? AWSTemporaryCredentials
                        ?: throw IllegalStateException("AWS temporary credentials could not be obtained.")
                    Thread {
                        try {
                            val profiles = components.mapNotNull { component ->
                                val producer = component.producerName
                                if (producer.isBlank() || component.hardwareVersion.isBlank() ||
                                    component.softwareVersion.isBlank() || component.hardwareVersion == "—" ||
                                    component.softwareVersion == "—") return@mapNotNull null
                                val base = "/prod/v1/profiles/$APP_ID/" + java.net.URLEncoder.encode(producer, "UTF-8") +
                                    "/" + java.net.URLEncoder.encode(component.hardwareVersion, "UTF-8") +
                                    "/" + java.net.URLEncoder.encode(component.softwareVersion, "UTF-8") + "/web"
                                val profile = signedGet("$base/profile.json", credentials)
                                val translations = signedGet("$base/trans_en.json", credentials)
                                EcoNetControllerProfile(component.name, producer, component.hardwareVersion,
                                    component.softwareVersion, JSONObject(profile), JSONObject(translations))
                            }
                            if (profiles.isEmpty()) throw IllegalStateException(
                                "The installation response did not provide controller profile identifiers for its components."
                            )
                            Handler(Looper.getMainLooper()).post { onSuccess(profiles) }
                        } catch (error: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                onError(error.message ?: "Could not load controller profiles.")
                            }
                        }
                    }.start()
                } catch (error: Exception) {
                    onError(error.message ?: "Could not prepare controller profile request.")
                }
            },
            { error -> onError(error.localizedMessage ?: "Could not retrieve AWS session.") }
        )
    }


    /**
     * Opens the installation's AWS IoT session using the signed-in Cognito identity,
     * discovers devices on the bus, then submits the installation's known component
     * metadata using the same REGISTER_COMPONENTS operation as the web client.
     * This is registration/metadata only; it does not write controller parameters.
     */
    fun connectAndRegisterComponents(
        installationId: String,
        components: List<EcoNetComponent>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Amplify.Auth.fetchAuthSession(
            { authSession ->
                try {
                    val session = authSession as? AWSCognitoAuthSession
                        ?: throw IllegalStateException("Cognito session was not available.")
                    if (!session.isSignedIn) throw IllegalStateException("Please sign in again.")
                    val identityId = session.identityIdResult.value
                        ?: throw IllegalStateException("Cognito Identity ID is unavailable. Check the Identity Pool configuration.")
                    val credentials = session.awsCredentialsResult.value as? AWSTemporaryCredentials
                        ?: throw IllegalStateException("AWS temporary credentials could not be obtained.")
                    val idToken = session.userPoolTokensResult.value?.idToken
                    val groups = idToken?.let { cognitoGroups(it) }.orEmpty()
                    // ecoNET maps ServiceCompanyManagers to SERVICE_ADMIN (role 10).
                    // The official web client uses serviceRequest/serviceResponse for this role.
                    val serviceRole = groups.contains("ServiceCompanyManagers")
                    val unsupportedGroups = groups.filterNot { it == "ServiceCompanyManagers" }
                    if (unsupportedGroups.isNotEmpty()) {
                        throw IllegalStateException("Unsupported ecoNET role groups ($unsupportedGroups). Only standard customer and ServiceCompanyManagers topic families are implemented.")
                    }
                    Thread {
                        var mqtt: EcoNetMqttClient? = null
                        try {
                            mqtt = EcoNetMqttClient(installationId, identityId, credentials, serviceRole)
                            mqtt.connect()
                            val ping = mqtt.request(JSONObject().put("name", "CLIENT_PING"))
                            val pingStatus = ping.optInt("statusCode", 0)
                            if (pingStatus != 0) throw IllegalStateException("MQTT broker connected, but CLIENT_PING returned status $pingStatus.")
                            val bus = mqtt.request(JSONObject().put("name", "GET_COMPONENTS_ON_BUS"))
                            val busTargets = bus.optJSONArray("targets") ?: JSONArray()
                            val registrationTargets = JSONArray()
                            components.forEach { component ->
                                if (component.serialNumber.isBlank() || component.serialNumber == "—" ||
                                    component.componentTypeId <= 0 || component.producerCode <= 0 ||
                                    component.hardwareVersion == "—" || component.softwareVersion == "—") return@forEach
                                val params = JSONObject()
                                    .put("componentType", component.componentType.ifBlank { component.name })
                                    .put("componentTypeId", component.componentTypeId)
                                    .put("hardware", component.hardwareVersion)
                                    .put("software", component.softwareVersion)
                                    .put("installationId", installationId)
                                    .put("isHidden", false)
                                    .put("manufacturer", component.producerName.ifBlank { "Unknown" })
                                    .put("manufacturerCode", component.producerCode)
                                    .put("serialNumber", component.serialNumber)
                                registrationTargets.put(JSONObject()
                                    .put("component", component.serialNumber)
                                    .put("parameters", params))
                            }
                            val registeredCount = if (registrationTargets.length() > 0) {
                                val result = mqtt.request(JSONObject()
                                    .put("name", "REGISTER_COMPONENTS")
                                    .put("targets", registrationTargets))
                                val targets = result.optJSONArray("targets") ?: JSONArray()
                                var accepted = 0
                                val rejected = mutableListOf<String>()
                                for (i in 0 until targets.length()) {
                                    val item = targets.optJSONObject(i) ?: continue
                                    when (item.optInt("statusCode", -1)) {
                                        0 -> accepted++
                                        else -> rejected.add("${item.optString("component")}: status ${item.optInt("statusCode", -1)}")
                                    }
                                }
                                if (rejected.isNotEmpty()) throw IllegalStateException("MQTT connected and discovered $busTargets component bus target(s), but component registration had failures: ${rejected.joinToString()}")
                                accepted
                            } else 0
                            val summary = "MQTT connected; CLIENT_PING acknowledged; discovered ${busTargets.length()} bus target(s); registered $registeredCount installation component(s)."
                            mqtt.disconnect()
                            Handler(Looper.getMainLooper()).post { onSuccess(summary) }
                        } catch (error: Exception) {
                            try { mqtt?.disconnect() } catch (_: Exception) {}
                            Handler(Looper.getMainLooper()).post {
                                onError(error.message ?: "Could not connect and register installation components.")
                            }
                        }
                    }.start()
                } catch (error: Exception) {
                    onError(error.message ?: "Could not prepare the authenticated MQTT session.")
                }
            },
            { error -> onError(error.localizedMessage ?: "Could not retrieve the authenticated AWS session.") }
        )
    }

    private fun cognitoGroups(idToken: String): List<String> {
        return try {
            val payload = idToken.split(".").getOrNull(1) ?: return emptyList()
            val decoded = String(android.util.Base64.decode(
                payload + "=".repeat((4 - payload.length % 4) % 4),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            ), Charsets.UTF_8)
            val groups = JSONObject(decoded).optJSONArray("cognito:groups") ?: return emptyList()
            buildList { for (i in 0 until groups.length()) add(groups.optString(i)) }
        } catch (_: Exception) { emptyList() }
    }

    private fun signedGet(path: String, credentials: AWSTemporaryCredentials): String {
        val request = DefaultRequest<Any>("execute-api").apply {
            setEndpoint(URI.create(API_ENDPOINT))
            setResourcePath(path)
            setHttpMethod(HttpMethodName.GET)
            addHeader("appid", APP_ID)
        }
        val signer = AWS4Signer().apply {
            setRegionName(AWS_REGION)
            setServiceName("execute-api")
        }
        signer.sign(request, BasicSessionCredentials(
            credentials.accessKeyId, credentials.secretAccessKey, credentials.sessionToken
        ))
        val connection = (URL("https://$API_HOST$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) ->
                if (!name.equals("host", ignoreCase = true)) setRequestProperty(name, value)
            }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(body).optString("message") } catch (_: Exception) { "" }
                throw IllegalStateException(if (detail.isNotBlank()) "Profile request failed (HTTP $code): $detail" else "Profile request failed with HTTP $code.")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun requestInstallationList(credentials: AWSTemporaryCredentials): List<EcoNetInstallation> {
        // Use the maintained AWS SDK signer rather than hand-rolling SigV4. The SDK
        // constructs the canonical request and injects the session-token/date headers.
        val request = DefaultRequest<Any>("execute-api").apply {
            setEndpoint(URI.create(API_ENDPOINT))
            setResourcePath(API_PATH)
            setHttpMethod(HttpMethodName.GET)
            addHeader("appid", APP_ID)
        }

        val signer = AWS4Signer()
        signer.setRegionName(AWS_REGION)
        signer.setServiceName("execute-api")
        signer.sign(
            request,
            BasicSessionCredentials(
                credentials.accessKeyId,
                credentials.secretAccessKey,
                credentials.sessionToken
            )
        )

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            // Host is generated by HttpURLConnection from API_URL. Do not attempt to
            // override this restricted header; all other SDK-signed headers are copied.
            request.headers.forEach { (name, value) ->
                if (!name.equals("host", ignoreCase = true)) {
                    setRequestProperty(name, value)
                }
            }
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
                    else "ecoNET Cloud returned HTTP $statusCode. AWS SDK signing was rejected by the service."
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
}
