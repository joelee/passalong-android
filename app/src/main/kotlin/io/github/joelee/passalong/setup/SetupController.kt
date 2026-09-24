package io.github.joelee.passalong.setup

import io.github.joelee.passalong.core.Core
import io.github.joelee.passalong.core.HostKeyInfo
import io.github.joelee.passalong.core.SetupAnswers
import io.github.joelee.passalong.data.AppFiles
import io.github.joelee.passalong.domain.describe
import io.github.joelee.passalong.domain.rethrowCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Everything the setup screen shows; the fields default as `passalong init` defaults them. */
data class SetupState(
    val host: String = "",
    val port: String = "22",
    val user: String = "passalong",
    val remotePath: String = "/srv/passalong",
    val deviceName: String = "",
    /** This device's public key line, to add to the server's `authorized_keys`. */
    val publicKey: String? = null,
    /** The host key the server presented, shown until the user confirms or declines it. */
    val hostKey: HostKeyInfo? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** The config was written; the choose screen can open. */
    val done: Boolean = false,
) {
    /** The port as a number, or null when it is not one from 1 to 65535. */
    val portNumber: Int?
        get() = port.trim().toIntOrNull()?.takeIf { it in 1..MAX_PORT }

    /** Whether the host key can be fetched: every field is filled and the port is valid. */
    val canFetch: Boolean
        get() =
            host.isNotBlank() && user.isNotBlank() && remotePath.isNotBlank() && deviceName.isNotBlank() &&
                portNumber != null && publicKey != null && !busy

    private companion object {
        const val MAX_PORT = 65535
    }
}

/**
 * `passalong init` as the app runs it: this device gets an identity, the user fills in the
 * server, the server's host key is fetched and its fingerprint confirmed, and only then is the
 * config written. Declining the fingerprint writes nothing.
 */
class SetupController(
    private val scope: CoroutineScope,
    private val core: Core,
    private val files: AppFiles,
    private val hostname: String,
    private val hostKeyTimeoutSecs: Long = DEFAULT_TIMEOUT_SECS,
    /** Where files are read and keys made: never the main thread. */
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(SetupState(deviceName = hostname))

    /** What the screen shows. */
    val state: StateFlow<SetupState> = _state

    /**
     * Finds this device's key, or makes one. Call it once to start; the screen shows the key
     * once it is there.
     */
    fun start() {
        _state.update { it.copy(busy = true) }
        scope.launch {
            val existing = withContext(io) { files.readPublicKey() }
            if (existing != null) {
                _state.update { it.copy(publicKey = existing, busy = false) }
            } else {
                makeKey(replace = false)
            }
        }
    }

    fun hostChanged(value: String) = _state.update { it.copy(host = value, hostKey = null) }

    fun portChanged(value: String) = _state.update { it.copy(port = value, hostKey = null) }

    fun userChanged(value: String) = _state.update { it.copy(user = value) }

    fun remotePathChanged(value: String) = _state.update { it.copy(remotePath = value) }

    fun deviceNameChanged(value: String) = _state.update { it.copy(deviceName = value) }

    /** Makes this device a new identity; with [replace], even when it has one. */
    fun generateKey(replace: Boolean) {
        _state.update { it.copy(busy = true) }
        scope.launch { makeKey(replace) }
    }

    private suspend fun makeKey(replace: Boolean) {
        val comment = _state.value.deviceName.ifBlank { hostname }
        runCatching { withContext(io) { core.generateIdentity(files.identity.path, comment, replace) } }
            .onSuccess { public -> _state.update { it.copy(publicKey = public, error = null, busy = false) } }
            .onFailure { err -> _state.update { it.copy(error = err.rethrowCancellation().describe(), busy = false) } }
    }

    /** Fetches the server's host key, to show its fingerprint. */
    fun fetchHostKey() {
        val current = _state.value
        val port = current.portNumber ?: return
        if (!current.canFetch) return
        _state.update { it.copy(busy = true, error = null, hostKey = null) }
        scope.launch {
            runCatching { core.fetchHostKey(current.host.trim(), port, hostKeyTimeoutSecs) }
                .onSuccess { found -> _state.update { it.copy(hostKey = found, busy = false) } }
                .onFailure {
                        err ->
                    _state.update { it.copy(error = err.rethrowCancellation().describe(), busy = false) }
                }
        }
    }

    /**
     * The user compared the fingerprint. Confirming writes the config with the key pinned;
     * declining writes nothing.
     */
    fun fingerprintMatches(confirmed: Boolean) {
        val current = _state.value
        val found = current.hostKey ?: return
        if (!confirmed) {
            _state.update { it.copy(hostKey = null, error = "nothing was written") }
            return
        }
        val answers =
            SetupAnswers(
                deviceName = current.deviceName.trim(),
                host = current.host.trim(),
                port = current.portNumber ?: return,
                user = current.user.trim(),
                hostKey = found.opensshLine,
                identityFile = files.identity.path,
                remotePath = current.remotePath.trim(),
            )
        _state.update { it.copy(busy = true) }
        scope.launch {
            runCatching {
                val text = core.renderConfig(answers)
                // As `init` does: the file must parse back before it is written.
                core.validateConfig(text, files.home.path, hostname)
                withContext(io) { files.writeConfig(text) }
            }.onSuccess {
                _state.update { it.copy(hostKey = null, done = true, error = null, busy = false) }
            }.onFailure { err ->
                _state.update { it.copy(hostKey = null, error = err.rethrowCancellation().describe(), busy = false) }
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECS = 10L
    }
}
