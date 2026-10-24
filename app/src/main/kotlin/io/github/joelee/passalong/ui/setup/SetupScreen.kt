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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joelee.passalong.core.HostKeyInfo
import io.github.joelee.passalong.setup.ServerKind
import io.github.joelee.passalong.setup.ServerStep
import io.github.joelee.passalong.setup.SetupController
import io.github.joelee.passalong.setup.SetupState
import kotlinx.coroutines.launch

/** `passalong init`: the kind of server, its details, this device's key, and what to confirm. */
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
            KindChoice(state.kind, controller::kindChanged)
            Spacer(Modifier.height(12.dp))
            when (state.kind) {
                ServerKind.SSH -> SshFields(state, controller)
                ServerKind.SERVER -> ServerFields(state, controller)
            }
            Spacer(Modifier.height(16.dp))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            Row {
                Button(onClick = controller::connect, enabled = state.canConnect) { Text("Connect") }
                if (state.busy) CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
    state.hostKey?.let { FingerprintDialog(state, it, controller) }
    when (val step = state.serverStep) {
        is ServerStep.ConfirmPin -> PinDialog(state, step, controller)
        is ServerStep.TypeApiKey -> ApiKeyDialog(step, controller)
        is ServerStep.ConfirmPlaintext -> PlaintextDialog(step, controller)
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindChoice(
    kind: ServerKind,
    onChange: (ServerKind) -> Unit,
) {
    Text("Server kind", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = kind == ServerKind.SSH,
            onClick = { onChange(ServerKind.SSH) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("SSH") }
        SegmentedButton(
            selected = kind == ServerKind.SERVER,
            onClick = { onChange(ServerKind.SERVER) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("passalong-server") }
    }
}

@Composable
private fun SshFields(
    state: SetupState,
    controller: SetupController,
) {
    Text(
        "One machine you own runs an SSH server with a storage directory. " +
            "This device pushes and pulls items there.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    Field("Server host name or address", state.host, controller::hostChanged)
    Field("SSH port", state.port, controller::portChanged, KeyboardType.Number)
    Field("User on the server", state.user, controller::userChanged)
    Field("Storage directory on the server", state.remotePath, controller::remotePathChanged)
    Field("Name for this device", state.deviceName, controller::deviceNameChanged)
    Spacer(Modifier.height(16.dp))
    PublicKeyCard(state, controller)
}

@Composable
private fun ServerFields(
    state: SetupState,
    controller: SetupController,
) {
    Text(
        "A passalong-server keeps a workspace for your devices. Its operator gives you the " +
            "server's address, the pin of its certificate when it is self-signed, and an API key " +
            "for this device.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    Field("Server URL, such as https://box.example:8443", state.url, controller::urlChanged, KeyboardType.Uri)
    Field("Name for this device", state.deviceName, controller::deviceNameChanged)
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

/** The pin the server presented, and the operator's to paste, as `init` asks. */
@Composable
private fun PinDialog(
    state: SetupState,
    step: ServerStep.ConfirmPin,
    controller: SetupController,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = controller::cancelPin,
        title = { Text("Confirm the server's pin") },
        text = {
            Column {
                Text("The server at ${state.url.trim()} presented a certificate whose public key has the pin")
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        step.presented.pin,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (step.presented.trusted) {
                    Text("The operating system trusts its certificate, so you may go without a pin.")
                } else {
                    Text(
                        "The operating system does not trust it, so it is pinned. " +
                            "Run this on the server and paste what it prints:",
                    )
                    Text("passalong-server tls fingerprint", fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Pin") },
                    singleLine = true,
                    isError = step.problem != null,
                    supportingText = { step.problem?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${step.attemptsLeft} tries left", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { controller.pinTyped(typed) }, enabled = typed.isNotBlank()) { Text("Pin it") }
        },
        dismissButton = {
            Row {
                if (step.presented.trusted) {
                    TextButton(onClick = controller::trustSystem) { Text("Trust the system") }
                }
                TextButton(onClick = controller::cancelPin) { Text("Cancel") }
            }
        },
    )
}

/** The API key, typed without being shown, as `init` asks. */
@Composable
private fun ApiKeyDialog(
    step: ServerStep.TypeApiKey,
    controller: SetupController,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = controller::cancelApiKey,
        title = { Text("API key from the server's operator") },
        text = {
            Column {
                Text(
                    "It starts with pal_ and is shown once when the operator creates it. " +
                        "It is kept on this device only.",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("API key (pal_...)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = step.problem != null,
                    supportingText = { step.problem?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${step.attemptsLeft} tries left", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { controller.apiKeyTyped(typed) }, enabled = typed.isNotBlank()) { Text("Use it") }
        },
        dismissButton = { TextButton(onClick = controller::cancelApiKey) { Text("Cancel") } },
    )
}

/** The server's claim that the workspace is not encrypted, for the user to confirm, as `init` asks. */
@Composable
private fun PlaintextDialog(
    step: ServerStep.ConfirmPlaintext,
    controller: SetupController,
) {
    val items = if (step.info.itemCount == 1L) "item" else "items"
    AlertDialog(
        onDismissRequest = { controller.plaintextConfirmed(false) },
        title = { Text("Is it meant to be unencrypted for now?") },
        text = {
            Column {
                Text(step.summary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The server says workspace ${step.info.workspace} is not encrypted " +
                        "(${step.info.itemCount} $items). " +
                        "If other devices use it encrypted, stop here: the server is not telling the truth.",
                )
            }
        },
        confirmButton = { TextButton(onClick = { controller.plaintextConfirmed(true) }) { Text("Yes") } },
        dismissButton = { TextButton(onClick = { controller.plaintextConfirmed(false) }) { Text("No") } },
    )
}
