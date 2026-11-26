package uk.co.econetconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EconetConnectScreen() }
    }
}

@Composable
private fun EconetConnectScreen() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberSession by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Not connected") }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Econet Connect", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text("Service Edition · Initial development build",
                    style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("Cloud account", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(email, { email = it }, label = { Text("Email address") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(rememberSession, { rememberSession = it })
                    Text("Keep me signed in")
                }
                Button(onClick = { status = "Authentication adapter not connected yet" },
                    modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
                Text("Connection: $status", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("Development status", style = MaterialTheme.typography.titleLarge)
                Text("• Native Android shell is in place")
                Text("• Cloud authentication is not yet implemented")
                Text("• No credentials are transmitted or stored by this build")
                Text("• Heating controls are deliberately unavailable until the cloud API and permissions are verified",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
