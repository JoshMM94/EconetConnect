package uk.co.econetconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.amplifyframework.core.Amplify
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var amplifyReady = false
    private var initializationError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.configure(applicationContext)
            amplifyReady = true
        } catch (error: Exception) {
            initializationError = error.localizedMessage ?: "Could not initialize secure cloud authentication."
        }

        setContent {
            EconetConnectScreen(
                amplifyReady = amplifyReady,
                initializationError = initializationError
            )
        }
    }
}

@Composable
private fun CircuitHomeScreen(
    installationName: String,
    circuitLabel: String,
    connectionLabel: String,
    profileLabel: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Home", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(installationName, style = MaterialTheme.typography.bodyMedium)
        Text(circuitLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(connectionLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Text(profileLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

        ParameterHomeCard("Target Room Temp", "Not loaded yet", "Requested room temperature setpoint")
        ParameterHomeCard("Current Room Temp", "Not loaded yet", "Measured room temperature")
        ParameterHomeCard("Hysteresis", "Not loaded yet", "Room temperature switching differential")
        ParameterHomeCard("Weather Compensation", "Not loaded yet", "Heating curve and curve shift")
    }
}

@Composable
private fun ParameterHomeCard(
    title: String,
    value: String,
    description: String
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun EcoNetCloudStyleValues(report: String) {
    val root = remember(report) { runCatching { JSONObject(report) }.getOrNull() }
    val components = root?.optJSONArray("components")
    if (components == null) { Text("No decoded values available yet."); return }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Live installation data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        for (i in 0 until components.length()) {
            val component = components.optJSONObject(i) ?: continue
            Text(component.optString("name", "Device"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val values = component.optJSONArray("values") ?: continue
            var shown = 0
            for (j in 0 until values.length()) {
                val item = values.optJSONObject(j) ?: continue
                if (item.has("_request")) continue
                val label = item.optString("label").ifBlank { item.optString("key") }
                val value = item.opt("value")?.toString() ?: "—"
                val unit = item.optString("unit")
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(value + if (unit.isNotBlank()) " $unit" else "", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                shown++
            }
            if (shown == 0) Text("No values returned for this device yet.", color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
fun EconetConnectScreen(
    amplifyReady: Boolean,
    initializationError: String?
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var awaitingChallenge by remember { mutableStateOf(false) }
    var isSignedIn by remember { mutableStateOf(false) }
    var installations by remember { mutableStateOf<List<EcoNetInstallation>>(emptyList()) }
    var installationsLoaded by remember { mutableStateOf(false) }
    var selectedInstallation by remember { mutableStateOf<EcoNetInstallation?>(null) }
    var selectedComponent by remember { mutableStateOf<EcoNetComponent?>(null) }
    var installationDetails by remember { mutableStateOf<EcoNetInstallationDetails?>(null) }
    var controllerProfiles by remember { mutableStateOf<List<EcoNetControllerProfile>>(emptyList()) }
    var profileStatus by remember { mutableStateOf("Controller profile not loaded") }
    var mqttStatus by remember { mutableStateOf("MQTT not connected") }
    var parameterDump by remember { mutableStateOf("") }
    var liveValues by remember { mutableStateOf("") }
    var liveValuesStatus by remember { mutableStateOf("Live values not requested yet") }
    var diagnosticReport by remember { mutableStateOf("") }
    var diagnosticStatus by remember { mutableStateOf("Diagnostics not run") }
    var isDiagnosing by remember { mutableStateOf(false) }
    var dumpStatus by remember { mutableStateOf("No parameter dump retrieved yet") }
    var isDumping by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var status by remember { mutableStateOf(initializationError ?: "Not connected") }

    fun showError(message: String) {
        isLoading = false
        status = message
    }

    fun openInstallation(installation: EcoNetInstallation) {
        selectedInstallation = installation
        selectedComponent = null
        installationDetails = null
        controllerProfiles = emptyList()
        liveValues = ""
        liveValuesStatus = "Requesting live controller values…"
        profileStatus = "Loading controller profile…"
        isLoading = true
        status = "Opening installation and retrieving configuration…"
        EcoNetCloudApi.fetchInstallationDetails(
            installation.id,
            onSuccess = { details ->
                installationDetails = details
                status = "Installation details retrieved. Loading controller profiles and live readings…"
                mqttStatus = "Live-value request will establish its own read-only MQTT session."
                profileStatus = "Loading profiles for discovered components…"
                EcoNetCloudApi.fetchControllerProfiles(
                    details.components,
                    onSuccess = { profiles ->
                        controllerProfiles = profiles
                        profileStatus = "Loaded ${profiles.size} controller profile(s)"
                        EcoNetCloudApi.fetchAvailableValues(
                            installationId = installation.id,
                            serviceId = details.serviceId,
                            components = details.components,
                            onSuccess = { report ->
                                liveValues = report
                                liveValuesStatus = "Live values received from controller"
                                isLoading = false
                            },
                            onError = { message ->
                                liveValuesStatus = "Live value request failed: $message"
                                isLoading = false
                            }
                        )
                    },
                    onError = { message ->
                        profileStatus = message
                        isLoading = false
                    }
                )
            },
            onError = { message -> isLoading = false; status = message }
        )
    }

    fun loadInstallations() {
        isLoading = true
        installationsLoaded = false
        status = "Retrieving installations securely from ecoNET Cloud…"
        EcoNetCloudApi.fetchInstallations(
            onSuccess = { result ->
                installations = result
                installationsLoaded = true
                isLoading = false
                status = if (result.isEmpty()) {
                    "Cloud connection succeeded, but this account returned no installations."
                } else {
                    "Retrieved ${result.size} installation(s) from ecoNET Cloud."
                }
            },
            onError = { message ->
                isLoading = false
                status = message
            }
        )
    }

    // Restore the persisted Cognito session when the activity is recreated.
    // Closing the app must not require the user to authenticate a second time.
    LaunchedEffect(amplifyReady) {
        if (amplifyReady) {
            isLoading = true
            status = "Checking for an existing ecoNET Cloud session…"
            Amplify.Auth.fetchAuthSession(
                { authSession ->
                    val cognitoSession = authSession as? AWSCognitoAuthSession
                    if (cognitoSession?.isSignedIn == true) {
                        isSignedIn = true
                        status = "Existing session restored. Retrieving your installations…"
                        loadInstallations()
                    } else {
                        isLoading = false
                        isSignedIn = false
                        status = "Not connected"
                    }
                },
                { error ->
                    isLoading = false
                    isSignedIn = false
                    status = "Could not restore the saved session: ${error.localizedMessage ?: "Please sign in."}"
                }
            )
        }
    }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Econet Connect",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Service Edition · Cloud connection")
                HorizontalDivider()

                if (selectedInstallation != null) {
                    val selected = selectedInstallation!!
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selectedInstallation = null; installationDetails = null; parameterDump = ""; status = "Installation list" }) {
                            Text("← Installations")
                        }
                        TextButton(onClick = { openInstallation(selected) }, enabled = !isLoading) { Text("Refresh") }
                    }
                    Text("DHW", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(installationDetails?.name ?: selected.name, style = MaterialTheme.typography.bodyMedium)
                    if (isLoading && installationDetails == null) CircularProgressIndicator()
                    installationDetails?.let { details ->
                        val controller = details.components.firstOrNull {
                            it.name.contains("Smart Wiring Centre", true) || it.componentType.contains("ecoMAX", true)
                        }
                        Text("Domestic hot water", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(liveValuesStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        if (liveValues.isBlank()) {
                            if (isLoading) CircularProgressIndicator()
                            Text("Waiting for profile-defined live readings. No temperatures or setpoints will be shown until the controller returns them.")
                        } else {
                            EcoNetCloudStyleValues(liveValues)
                        }
                        Text("Controller profile: $profileStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Text("MQTT: $mqttStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                } else if (isSignedIn) {
                    Text("Cloud account", style = MaterialTheme.typography.titleLarge)
                    Text("Signed in as $email")
                    Button(
                        onClick = { loadInstallations() },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (installationsLoaded) "Refresh installations" else "Load installations")
                        }
                    }

                    if (installationsLoaded && installations.isEmpty()) {
                        Text("No installations were returned for this account.")
                    }

                    installations.forEach { installation ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(enabled = !isLoading) { openInstallation(installation) }) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(installation.name, style = MaterialTheme.typography.titleMedium)
                                Text("Serial: ${installation.factoryNumber}")
                                if (installation.address.isNotBlank()) Text(installation.address)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (installation.connected) "Connected" else "Not connected")
                                    if (installation.hasAlarms) Text("· Alarms present")
                                }
                                if (installation.softwareVersion.isNotBlank()) {
                                    Text("Software: ${installation.softwareVersion}")
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            Amplify.Auth.signOut { _ ->
                                isSignedIn = false
                                email = ""
                                installations = emptyList()
                                installationsLoaded = false
                                status = "Signed out"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) { Text("Sign out") }
                } else {
                    Text("Cloud account", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = amplifyReady && !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!awaitingChallenge) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            enabled = amplifyReady && !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = verificationCode,
                            onValueChange = { verificationCode = it },
                            label = { Text("Verification code") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            if (!amplifyReady) {
                                showError(initializationError ?: "Authentication is unavailable.")
                            } else if (awaitingChallenge) {
                                if (verificationCode.isBlank()) {
                                    showError("Enter the verification code sent by ecoNET.")
                                } else {
                                    isLoading = true
                                    Amplify.Auth.confirmSignIn(
                                        verificationCode.trim(),
                                        { result ->
                                            isLoading = false
                                            if (result.isSignedIn) {
                                                isSignedIn = true
                                                awaitingChallenge = false
                                                password = ""
                                                verificationCode = ""
                                                status = "Signed in. Retrieving your installations…"
                                                loadInstallations()
                                            } else {
                                                awaitingChallenge = true
                                                status = "Another sign-in step is required: ${result.nextStep.signInStep}"
                                            }
                                        },
                                        { error -> showError(error.localizedMessage ?: "Verification failed.") }
                                    )
                                }
                            } else if (email.isBlank() || password.isBlank()) {
                                showError("Enter your ecoNET email address and password.")
                            } else {
                                isLoading = true
                                status = "Connecting securely to ecoNET Cloud…"
                                Amplify.Auth.signIn(
                                    email.trim(),
                                    password,
                                    { result ->
                                        isLoading = false
                                        if (result.isSignedIn) {
                                            isSignedIn = true
                                            password = ""
                                            status = "Signed in. Retrieving your installations…"
                                            loadInstallations()
                                        } else {
                                            awaitingChallenge = true
                                            status = "Your account requires another sign-in step: ${result.nextStep.signInStep}"
                                        }
                                    },
                                    { error -> showError(error.localizedMessage ?: "Sign-in failed.") }
                                )
                            }
                        },
                        enabled = amplifyReady && !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (awaitingChallenge) "Confirm code" else "Sign in")
                        }
                    }
                }

                HorizontalDivider()
                Text("Connection", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Your password is entered directly into the app. Authentication is handled by AWS Cognito; credentials are not sent to this development project or displayed in logs.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
