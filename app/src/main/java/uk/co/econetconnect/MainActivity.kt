package uk.co.econetconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.amplifyframework.core.Amplify
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin

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
    var status by remember {
        mutableStateOf(initializationError ?: "Not connected")
    }

    fun showError(message: String) {
        isLoading = false
        status = message
    }

    fun handleSignInResult(signedIn: Boolean, nextStep: String) {
        isLoading = false
        if (signedIn) {
            isSignedIn = true
            awaitingChallenge = false
            password = ""
            verificationCode = ""
            status = "Signed in successfully. Installation access is the next step."
        } else {
            awaitingChallenge = true
            status = "Your account requires another sign-in step: $nextStep"
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

                if (isSignedIn) {
                    Text("Cloud account", style = MaterialTheme.typography.titleLarge)
                    Text("Signed in as $email")
                    Text(
                        "Authentication is working. Installation retrieval will be enabled in the next build.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            Amplify.Auth.signOut(
                                { 
                                    isSignedIn = false
                                    email = ""
                                    status = "Signed out"
                                },
                                { error -> showError(error.localizedMessage ?: "Sign out failed.") }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
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
                                            handleSignInResult(
                                                result.isSignedIn,
                                                result.nextStep.signInStep.toString()
                                            )
                                        },
                                        { error ->
                                            showError(error.localizedMessage ?: "Verification failed.")
                                        }
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
                                        handleSignInResult(
                                            result.isSignedIn,
                                            result.nextStep.signInStep.toString()
                                        )
                                    },
                                    { error ->
                                        showError(error.localizedMessage ?: "Sign-in failed.")
                                    }
                                )
                            }
                        },
                        enabled = amplifyReady && !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
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
