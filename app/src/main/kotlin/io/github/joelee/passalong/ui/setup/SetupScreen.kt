package io.github.joelee.passalong.ui.setup

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joelee.passalong.core.HostKeyInfo
import io.github.joelee.passalong.setup.SetupController
import io.github.joelee.passalong.setup.SetupState
import kotlinx.coroutines.launch

/** `passalong init`: the server, this device's key, and the host key to confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    controller: SetupController,
    coreVersion: String,
    onDone: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(topBar = { TopAppBar(title = { Text("Set up passalong $coreVersion") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(
                "One machine you own runs an SSH server with a storage directory. " +
                    "This device pushes and pulls items there.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Field("Server host name or address", state.host, controller::hostChanged)
            Field("SSH port", state.port, controller::portChanged, KeyboardType.Number)
            Field("User on the server", state.user, controller::userChanged)
            Field("Storage directory on the server", state.remotePath, controller::remotePathChanged)
            Field("Name for this device", state.deviceName, controller::deviceNameChanged)
            Spacer(Modifier.height(16.dp))
            PublicKeyCard(state, controller)
            Spacer(Modifier.height(16.dp))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            Row {
                Button(onClick = controller::fetchHostKey, enabled = state.canFetch) { Text("Connect") }
                if (state.busy) CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
    state.hostKey?.let { FingerprintDialog(state, it, controller) }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun PublicKeyCard(
    state: SetupState,
    controller: SetupController,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("This device's public key", style = MaterialTheme.typography.titleSmall)
            Text(
                "Add it to ~${state.user.ifBlank { "passalong" }}/.ssh/authorized_keys on the server.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val key = state.publicKey
            if (key == null) {
                Text("No key yet.")
            } else {
                SelectionContainer {
                    Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row {
                TextButton(
                    onClick = {
                        key?.let {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipData.newPlainText("passalong", it).toClipEntry(),
                                )
                            }
                        }
                    },
                    enabled = key != null,
                ) {
                    Text("Copy")
                }
                TextButton(onClick = { controller.generateKey(replace = true) }) {
                    Text(if (key == null) "Generate" else "Replace")
                }
            }
        }
    }
}

@Composable
private fun FingerprintDialog(
    state: SetupState,
    found: HostKeyInfo,
    controller: SetupController,
) {
    AlertDialog(
        onDismissRequest = { controller.fingerprintMatches(false) },
        title = { Text("Does the fingerprint match?") },
        text = {
            Column {
                val address = "${state.host.trim()}:${state.port.trim()}"
                Text("The server at $address presented this ${found.algorithm} host key:")
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(found.fingerprint, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Text("Compare it with the server's own key, for example by running there:")
                Text("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = { controller.fingerprintMatches(true) }) { Text("Yes, pin it") } },
        dismissButton = { TextButton(onClick = { controller.fingerprintMatches(false) }) { Text("No") } },
    )
}
