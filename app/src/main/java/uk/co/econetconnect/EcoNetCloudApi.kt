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
    val serviceId: String = "",
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
                                components = components, invalidComponents = invalid,
                                serviceId = info.optString("serviceId").takeIf { it.isNotBlank() && it != "null" } ?: ""
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
                                // ecoNET's web client retrieves profiles from the Archives API,
                                // not the signed AWS execute-api endpoint.
                                val profileName = component.componentType.ifBlank { component.name }
                                val encodedName = java.net.URLEncoder.encode(profileName, "UTF-8")
                                val encodedHardware = java.net.URLEncoder.encode(component.hardwareVersion, "UTF-8")
                                val encodedSoftware = java.net.URLEncoder.encode(component.softwareVersion, "UTF-8")
                                val profilePath = "/prod/v1/profiles/" + APP_ID + "/" + encodedName + "/" + encodedHardware + "/" + encodedSoftware + "/web/profile.json"
                                val translationsPath = "/prod/v1/profiles/" + APP_ID + "/" + encodedName + "/" + encodedHardware + "/" + encodedSoftware + "/web/trans_en.json"
                                val profile = signedGet(profilePath, credentials)
                                val translations = signedGet(translationsPath, credentials)
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
        serviceId: String,
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
                    val idToken = session.userPoolTokensResult.value?.idToken
                        ?: throw IllegalStateException("Cognito ID token is unavailable.")
                    val credentials = session.awsCredentialsResult.value as? AWSTemporaryCredentials
                        ?: throw IllegalStateException("AWS temporary credentials could not be obtained.")
                    val identityId = session.identityIdResult.value
                        ?: throw IllegalStateException("Cognito Identity Pool identity ID is unavailable.")
                    // Official Cloud: group -> role; service roles use installation.serviceId,
                    // default users use the Cognito Identity Pool identityId.
                    val mqttPrincipal = resolveMqttPrincipal(idToken, identityId, serviceId)
                    val mqttClientId = mqttPrincipal.first
                    val serviceRole = mqttPrincipal.second
                    Thread {
                        var mqtt: EcoNetMqttClient? = null
                        try {
                            mqtt = EcoNetMqttClient(installationId, mqttClientId, credentials, serviceRole)
                            mqtt.connect()
                            val ping = mqtt.request(JSONObject().put("name", "CLIENT_PING"))
                            val pingStatus = ping.optInt("statusCode", -1)
                            // Preserve non-zero operation status for diagnosis, but don't assume
                            // CLIENT_PING status 2 means the MQTT session itself is unusable.
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
                            val summary = "MQTT connected; CLIENT_PING status $pingStatus; discovered ${busTargets.length()} bus target(s); registered $registeredCount installation component(s).";
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


    fun runTransportDiagnostics(
        installationId: String,
        serviceId: String,
        components: List<EcoNetComponent>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Amplify.Auth.fetchAuthSession({ authSession ->
            try {
                val session = authSession as? AWSCognitoAuthSession
                    ?: throw IllegalStateException("Cognito session unavailable.")
                if (!session.isSignedIn) throw IllegalStateException("Please sign in again.")
                val idToken = session.userPoolTokensResult.value?.idToken
                    ?: throw IllegalStateException("Cognito ID token unavailable.")
                val groups = cognitoGroups(idToken)
                val credentials = session.awsCredentialsResult.value as? AWSTemporaryCredentials
                    ?: throw IllegalStateException("AWS temporary credentials unavailable.")
                // Match the reference client's DEFAULT identity path.
                // Official ecoNET principal: serviceId for service roles, otherwise userId.
                // Cognito Identity Pool UUID is only the AWS credential identity, not the MQTT principal.
                val identityId = session.identityIdResult.value
                    ?: throw IllegalStateException("Cognito Identity Pool identity ID is unavailable.")
                val mqttPrincipal = resolveMqttPrincipal(idToken, identityId, serviceId)
                val mqttClientId = mqttPrincipal.first
                val serviceRole = mqttPrincipal.second
                Thread {
                    val report = StringBuilder()
                    report.appendLine("Econet Connect transport diagnostics")
                    report.appendLine("Installation ID: $installationId")
                    report.appendLine("Components returned by installation API: ${components.size}")
                    components.forEach { c ->
                        report.appendLine("- ${c.name}: serial=${c.serialNumber}, type=${c.componentType}, typeId=${c.componentTypeId}, producerCode=${c.producerCode}, hardware=${c.hardwareVersion}, software=${c.softwareVersion}, protocol=${c.protocol}")
                    }
                    report.appendLine("Cognito Identity Pool identity ID present: ${session.identityIdResult.value?.isNotBlank() == true}")
                    report.appendLine("MQTT identity selected: Cognito Identity Pool identity ID (reference client convention)")
                    report.appendLine("Cognito groups: ${if (groups.isEmpty()) "(none in token)" else groups.joinToString()}")
                    report.appendLine("Credentials: temporary session credentials available (secret values deliberately excluded)")
                    try {
                        val probe = EcoNetMqttClient(installationId, mqttClientId, credentials, serviceRole)
                        report.appendLine()
                        report.appendLine("AWS IoT WebSocket handshake probe")
                        report.append(probe.diagnoseWebSocketHandshake("installation diagnostics"))
                        probe.disconnect()
                    } catch (e: Exception) {
                        report.appendLine("Probe setup failed: ${e.message}")
                    }
                    Handler(Looper.getMainLooper()).post { onSuccess(report.toString()) }
                }.start()
            } catch (e: Exception) {
                onError(e.message ?: "Could not prepare transport diagnostics.")
            }
        }, { e -> onError(e.localizedMessage ?: "Could not retrieve authenticated AWS session.") })
    }

    /**
     * Read-only discovery: load profile-defined parameter keys, then ask the
     * installation for values using the reference client's GET_VALUES operation.
     * No circuit numbering or fixed parameter IDs are assumed.
     */
    /**
     * Read raw parameter tables directly from the controller. This deliberately does not
     * depend on profile downloads, so a profile API 403 cannot block live-value discovery.
     * Mirrors the official client's PARAM_TABLE_DUMP request and is read-only.
     */
    fun fetchRawParameterDump(
        installationId: String,
        serviceId: String,
        components: List<EcoNetComponent>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Amplify.Auth.fetchAuthSession({ authSession ->
            try {
                val session = authSession as? AWSCognitoAuthSession
                    ?: throw IllegalStateException("Cognito session was not available.")
                if (!session.isSignedIn) throw IllegalStateException("Please sign in again.")
                val idToken = session.userPoolTokensResult.value?.idToken
                    ?: throw IllegalStateException("Cognito ID token is unavailable.")
                val credentials = session.awsCredentialsResult.value as? AWSTemporaryCredentials
                    ?: throw IllegalStateException("AWS temporary credentials are unavailable.")
                val identityId = session.identityIdResult.value
                    ?: throw IllegalStateException("Cognito Identity Pool identity ID is unavailable.")
                val principal = resolveMqttPrincipal(idToken, identityId, serviceId)
                Thread {
                    var mqtt: EcoNetMqttClient? = null
                    try {
                        mqtt = EcoNetMqttClient(installationId, principal.first, credentials, principal.second)
                        mqtt.connect()
                        val output = JSONObject()
                            .put("installationId", installationId)
                            .put("retrievedAtUtc", java.time.Instant.now().toString())
                            .put("method", "PARAM_TABLE_DUMP")
                            .put("mqttPrincipalType", if (principal.second) "service" else "default")
                            .put("components", JSONArray())
                            .put("failedComponents", JSONArray())
                        components.forEach { component ->
                            if (component.serialNumber.isBlank() || component.serialNumber == "—") {
                                output.getJSONArray("failedComponents").put(JSONObject()
                                    .put("name", component.name).put("error", "Missing component serial number"))
                            } else {
                                try {
                                    val response = mqtt!!.request(JSONObject()
                                        .put("name", "PARAM_TABLE_DUMP")
                                        .put("targets", JSONArray().put(JSONObject()
                                            .put("component", component.serialNumber).put("isRaw", 1))))
                                    val targets = response.optJSONArray("targets") ?: JSONArray()
                                    val target = targets.optJSONObject(0)
                                    if (target == null || target.optInt("statusCode", -1) != 0) {
                                        output.getJSONArray("failedComponents").put(JSONObject()
                                            .put("name", component.name).put("serialNumber", component.serialNumber)
                                            .put("response", target ?: response))
                                    } else {
                                        output.getJSONArray("components").put(JSONObject()
                                            .put("name", component.name)
                                            .put("serialNumber", component.serialNumber)
                                            .put("componentType", component.componentType)
                                            .put("hardwareVersion", component.hardwareVersion)
                                            .put("softwareVersion", component.softwareVersion)
                                            .put("parameters", target.opt("parameters") ?: JSONObject()))
                                    }
                                } catch (e: Exception) {
                                    output.getJSONArray("failedComponents").put(JSONObject()
                                        .put("name", component.name).put("serialNumber", component.serialNumber)
                                        .put("error", e.message ?: e.javaClass.simpleName))
                                }
                            }
                        }
                        if (output.getJSONArray("components").length() == 0) {
                            throw IllegalStateException("MQTT connected, but no component returned a successful PARAM_TABLE_DUMP. " +
                                output.getJSONArray("failedComponents").toString())
                        }
                        Handler(Looper.getMainLooper()).post { onSuccess(output.toString(2)) }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post { onError(e.message ?: "Raw parameter retrieval failed.") }
                    } finally {
                        try { mqtt?.disconnect() } catch (_: Exception) {}
                    }
                }.start()
            } catch (e: Exception) {
                onError(e.message ?: "Could not prepare raw parameter request.")
            }
        }, { error -> onError(error.localizedMessage ?: "Could not retrieve authenticated AWS session.") })
    }

    fun fetchAvailableValues(
        installationId: String,
        serviceId: String,
        components: List<EcoNetComponent>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        fetchControllerProfiles(components, { profiles ->
            Amplify.Auth.fetchAuthSession({ authSession ->
                try {
                    val session = authSession as? AWSCognitoAuthSession
                        ?: throw IllegalStateException("Cognito session was not available.")
                    if (!session.isSignedIn) throw IllegalStateException("Please sign in again.")
                    val credentials = session.awsCredentialsResult.value as? AWSTemporaryCredentials
                        ?: throw IllegalStateException("AWS temporary credentials are unavailable.")
                    val identityId = session.identityIdResult.value
                        ?: throw IllegalStateException("Cognito Identity Pool identity ID is unavailable.")
                    Thread {
                        var mqtt: EcoNetMqttClient? = null
                        try {
                            val output = JSONObject().put("installationId", installationId)
                                .put("retrievedAtUtc", java.time.Instant.now().toString())
                                .put("method", "GET_VALUES").put("components", JSONArray())
                            val failures = JSONArray()
                            mqtt = EcoNetMqttClient(installationId, identityId, credentials, false)
                            mqtt.connect()
                            profiles.forEach { profile ->
                                val component = components.firstOrNull { it.name == profile.componentName }
                                if (component == null || component.serialNumber.isBlank() || component.serialNumber == "—") {
                                    failures.put(JSONObject().put("component", profile.componentName).put("error", "No componentFn identifier"))
                                } else {
                                    val labels = linkedMapOf<String, JSONObject>()
                                    fun visit(node: Any?, parentTitle: String? = null) {
                                        when (node) {
                                            is JSONObject -> {
                                                val title = node.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: parentTitle
                                                val unit = node.optString("unit").takeIf { it.isNotBlank() && it != "null" }
                                                listOf(node.optString("parameterName"), node.optString("parameter")).filter { it.isNotBlank() && it != "null" }.forEach { key ->
                                                    labels[key] = JSONObject().put("label", title ?: key).put("unit", unit ?: "")
                                                }
                                                val it = node.keys(); while (it.hasNext()) visit(node.opt(it.next()), title)
                                            }
                                            is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), parentTitle)
                                        }
                                    }
                                    visit(profile.profile)
                                    val translations = profile.translations
                                    labels.forEach { (key, meta) ->
                                        val title = meta.optString("label")
                                        val translated = translations.optString(title).takeIf { it.isNotBlank() && it != "null" }
                                            ?: translations.optString(title.removePrefix("@")).takeIf { it.isNotBlank() && it != "null" }
                                            ?: translations.optString(key).takeIf { it.isNotBlank() && it != "null" }
                                        if (translated != null) meta.put("label", translated)
                                    }
                                    val keys = labels.keys.toList()
                                    if (keys.isEmpty()) {
                                        failures.put(JSONObject().put("component", component.name).put("error", "Profile contained no parameter keys"))
                                    } else {
                                        val valueRows = JSONArray()
                                        keys.chunked(100).forEachIndexed { index, chunk ->
                                            val target = JSONObject().put("component", component.serialNumber).put("parameters", JSONArray(chunk))
                                            val response = mqtt!!.requestEnvelope(JSONObject().put("name", "GET_VALUES").put("targets", JSONArray().put(target)))
                                            fun extract(node: Any?) {
                                                when (node) {
                                                    is JSONObject -> {
                                                        val it = node.keys(); while (it.hasNext()) {
                                                            val key = it.next(); val raw = node.opt(key)
                                                            if (labels.containsKey(key)) {
                                                                val value = when (raw) {
                                                                    is JSONObject -> raw.opt("value") ?: raw.opt("0") ?: raw.opt("1")
                                                                    is JSONArray -> if (raw.length() > 0) raw.opt(0) else JSONObject.NULL
                                                                    else -> raw
                                                                }
                                                                if (value != null && value != JSONObject.NULL && value !is JSONObject && value !is JSONArray) {
                                                                    val meta = labels.getValue(key)
                                                                    valueRows.put(JSONObject().put("key", key).put("label", meta.optString("label", key))
                                                                        .put("unit", meta.optString("unit")).put("value", value))
                                                                } else extract(raw)
                                                            } else extract(raw)
                                                        }
                                                    }
                                                    is JSONArray -> for (i in 0 until node.length()) extract(node.opt(i))
                                                }
                                            }
                                            extract(response)
                                            valueRows.put(JSONObject().put("_request", "Batch ${index + 1}: ${chunk.size} keys").put("response", response))
                                        }
                                        output.getJSONArray("components").put(JSONObject().put("name", component.name)
                                            .put("componentFn", component.serialNumber).put("componentType", component.componentType)
                                            .put("profileParameterCount", keys.size).put("values", valueRows))
                                    }
                                }
                            }
                            output.put("failedComponents", failures)
                            if (output.getJSONArray("components").length() == 0) throw IllegalStateException("No component values could be requested. $failures")
                            Handler(Looper.getMainLooper()).post { onSuccess(output.toString(2)) }
                        } catch (e: Exception) { Handler(Looper.getMainLooper()).post { onError(e.message ?: "Could not retrieve ecoNET values.") } }
                        finally { try { mqtt?.disconnect() } catch (_: Exception) {} }
                    }.start()
                } catch (e: Exception) { onError(e.message ?: "Could not prepare value request.") }
            }, { error -> onError(error.localizedMessage ?: "Could not retrieve AWS session.") })
        }, { error -> onError("Could not load controller profiles: $error") })
    }
    fun fetchParameterTableDump(
        installationId: String,
        serviceId: String,
        components: List<EcoNetComponent>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Amplify.Auth.fetchAuthSession({ authSession ->
            try {
                val session = authSession as? AWSCognitoAuthSession
                    ?: throw IllegalStateException("Cognito session was not available.")
                if (!session.isSignedIn) throw IllegalStateException("Please sign in again.")
                val idToken = session.userPoolTokensResult.value?.idToken
                    ?: throw IllegalStateException("Cognito ID token unavailable.")
                val credentials = session.awsCredentialsResult.value as? AWSTemporaryCredentials
                    ?: throw IllegalStateException("AWS temporary credentials are unavailable.")
                val identityId = session.identityIdResult.value
                    ?: throw IllegalStateException("Cognito Identity Pool identity ID is unavailable.")
                val mqttPrincipal = resolveMqttPrincipal(idToken, identityId, serviceId)
                val mqttClientId = mqttPrincipal.first
                val serviceRole = mqttPrincipal.second
                Thread {
                    var mqtt: EcoNetMqttClient? = null
                    try {
                        mqtt = EcoNetMqttClient(installationId, mqttClientId, credentials, serviceRole)
                        mqtt.connect()
                        val ping = mqtt.request(JSONObject().put("name", "CLIENT_PING"))
                        val pingStatus = ping.optInt("statusCode", -1)
                        // Keep the complete response: status 2 is not yet understood and
                        // must not prevent us testing the subsequent documented operations.
                        val bus = mqtt.request(JSONObject().put("name", "GET_COMPONENTS_ON_BUS"))
                        val busTargets = bus.optJSONArray("targets") ?: JSONArray()
                        val registrationTargets = JSONArray()
                        components.forEach { component ->
                            if (component.serialNumber.isBlank() || component.serialNumber == "—" ||
                                component.componentTypeId <= 0 || component.producerCode <= 0 ||
                                component.hardwareVersion == "—" || component.softwareVersion == "—") return@forEach
                            val metadata = JSONObject()
                                .put("componentType", component.componentType.ifBlank { component.name })
                                .put("componentTypeId", component.componentTypeId)
                                .put("hardware", component.hardwareVersion)
                                .put("software", component.softwareVersion)
                                .put("installationId", installationId)
                                .put("isHidden", false)
                                .put("manufacturer", component.producerName.ifBlank { "Unknown" })
                                .put("manufacturerCode", component.producerCode)
                                .put("serialNumber", component.serialNumber)
                            registrationTargets.put(JSONObject().put("component", component.serialNumber).put("parameters", metadata))
                        }
                        var registrationResponse = JSONObject().put("skipped", true)
                        if (registrationTargets.length() > 0) {
                            registrationResponse = mqtt.request(JSONObject()
                                .put("name", "REGISTER_COMPONENTS")
                                .put("targets", registrationTargets))
                        }
                        val output = JSONObject()
                            .put("installationId", installationId)
                            .put("retrievedAtUtc", java.time.Instant.now().toString())
                            .put("operation", "PARAM_TABLE_DUMP")
                            .put("clientPingStatus", pingStatus)
                            .put("clientPingResponse", ping)
                            .put("getComponentsOnBusResponse", bus)
                            .put("registerComponentsResponse", registrationResponse)
                            .put("components", JSONArray())
                        val failures = JSONArray()
                        var successCount = 0
                        components.filter { it.serialNumber.isNotBlank() && it.serialNumber != "—" }.forEach { component ->
                            try {
                                val response = mqtt.request(JSONObject().put("name", "PARAM_TABLE_DUMP")
                                    .put("targets", JSONArray().put(JSONObject()
                                        .put("component", component.serialNumber).put("isRaw", 1))))
                                val targets = response.optJSONArray("targets") ?: JSONArray()
                                val target = targets.optJSONObject(0)
                                if (target == null || target.optInt("statusCode", -1) != 0) {
                                    failures.put(JSONObject().put("serialNumber", component.serialNumber)
                                        .put("name", component.name).put("response", target ?: JSONObject()))
                                } else {
                                    output.getJSONArray("components").put(JSONObject()
                                        .put("name", component.name).put("serialNumber", component.serialNumber)
                                        .put("componentType", component.componentType)
                                        .put("softwareVersion", component.softwareVersion)
                                        .put("hardwareVersion", component.hardwareVersion)
                                        .put("parameters", target.opt("parameters") ?: JSONObject()))
                                    successCount++
                                }
                            } catch (e: Exception) {
                                failures.put(JSONObject().put("serialNumber", component.serialNumber)
                                    .put("name", component.name).put("error", e.message ?: e.javaClass.simpleName))
                            }
                        }
                        output.put("failedComponents", failures)
                        if (successCount == 0) throw IllegalStateException("No component returned a successful parameter dump. $failures")
                        Handler(Looper.getMainLooper()).post { onSuccess(output.toString(2)) }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post { onError(e.message ?: "Parameter dump failed.") }
                    } finally {
                        try { mqtt?.disconnect() } catch (_: Exception) {}
                    }
                }.start()
            } catch (e: Exception) {
                onError(e.message ?: "Could not prepare parameter-table request.")
            }
        }, { error -> onError(error.localizedMessage ?: "Could not retrieve authenticated AWS session.") })
    }

    private fun resolveMqttPrincipal(
        idToken: String,
        identityId: String,
        installationServiceId: String
    ): Pair<String, Boolean> {
        val payload = idToken.split(".").getOrNull(1)
            ?: throw IllegalStateException("Cognito ID token payload is missing.")
        val decoded = String(
            android.util.Base64.decode(
                payload + "=".repeat((4 - payload.length % 4) % 4),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            ), Charsets.UTF_8
        )
        val claims = JSONObject(decoded)
        val groups = claims.optJSONArray("cognito:groups") ?: JSONArray()
        val groupNames = (0 until groups.length()).map { groups.optString(it) }
        // Mirrors the official client's jA() precedence and role mapping.
        val role = when {
            "ServiceUsers" in groupNames -> 7
            "VatServicemanUsers" in groupNames -> 8
            "ServiceModerators" in groupNames -> 9
            "ServiceCompanyManagers" in groupNames -> 10
            "PlumServiceUsers" in groupNames -> 11
            "ModeratorUsers" in groupNames -> 12
            "ProducerUsers" in groupNames -> 1
            "DistributorUsers" in groupNames -> 4
            "DistributorModerators" in groupNames -> 5
            "DistributorManagers" in groupNames -> 6
            "ProducerModerators" in groupNames -> 2
            "ProducerManagers" in groupNames -> 3
            "AdminUsers" in groupNames -> 13
            "PlumProducerUsers" in groupNames -> 14
            "RootUsers" in groupNames -> 15
            else -> 0
        }
        return if (role == 0) identityId to false else {
            if (installationServiceId.isBlank()) {
                throw IllegalStateException(
                    "Official role $role requires installation.serviceId, but the installation details API returned it empty."
                )
            }
            installationServiceId to true
        }
    }

    private fun cognitoSubject(idToken: String): String? = try {
        val payload = idToken.split(".").getOrNull(1) ?: return null
        val decoded = String(android.util.Base64.decode(payload + "=".repeat((4 - payload.length % 4) % 4), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), Charsets.UTF_8)
        JSONObject(decoded).optString("sub").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

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

    private fun fetchArchiveResource(path: String): String {
        val url = URL("https://api.econetcloud.eu/api" + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Controller profile request failed (HTTP " + code + ") at " + path + ".")
            }
            return body
        } finally {
            connection.disconnect()
        }
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
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Origin", "https://cloud.grant.eu")
            setRequestProperty("Referer", "https://cloud.grant.eu/")
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
