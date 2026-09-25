package io.github.joelee.passalong.setup

import io.github.joelee.passalong.core.Core
import io.github.joelee.passalong.core.HostKeyInfo
import io.github.joelee.passalong.core.HttpsSetupAnswers
import io.github.joelee.passalong.core.PresentedCertificate
import io.github.joelee.passalong.core.ServerInfo
import io.github.joelee.passalong.core.SetupAnswers
import io.github.joelee.passalong.data.AppFiles
import io.github.joelee.passalong.domain.Formats
import io.github.joelee.passalong.domain.describe
import io.github.joelee.passalong.domain.rethrowCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** The kinds of server the app can be set up for, as `init`'s first question offers them. */
enum class ServerKind {
    /** A plain SSH server with a storage directory. */
    SSH,

    /** A passalong-server workspace, over HTTPS with an API key. */
    SERVER,
}

/** What the setup flow for a passalong-server is waiting for. */
sealed interface ServerStep {
    /** The certificate was seen; the user confirms its pin, or trusts the system when it vouches. */
    data class ConfirmPin(val presented: PresentedCertificate, val attemptsLeft: Int, val problem: String?) : ServerStep

    /** The API key from the server's operator is being typed. */
    data class TypeApiKey(val attemptsLeft: Int, val problem: String?) : ServerStep

    /**
     * The server said the workspace is not encrypted and this device holds no store key; the
     * user confirms that before anything is written.
     */
    data class ConfirmPlaintext(val info: ServerInfo, val summary: String) : ServerStep
}

/** What the setup flow needs besides the core and the files; the defaults are for the app. */
data class SetupOptions(
    /** How long a host key fetch may take. */
    val hostKeyTimeoutSecs: Long = DEFAULT_TIMEOUT_SECS,
    /** Where files are read and keys made: never the main thread. */
    val io: CoroutineContext = Dispatchers.IO,
    /** The time, for the key's expiry. */
    val now: () -> Instant = Instant::now,
) {
    private companion object {
        const val DEFAULT_TIMEOUT_SECS = 10L
    }
}

/** Everything the setup screen shows; the fields default as `passalong init` defaults them. */
data class SetupState(
    val kind: ServerKind = ServerKind.SSH,
    val host: String = "",
    val port: String = "22",
    val user: String = "passalong",
    val remotePath: String = "/srv/passalong",
    /** The passalong-server's URL, such as `https://box.example:8443`. */
    val url: String = "",
    val deviceName: String = "",
    /** This device's public key line, to add to the server's `authorized_keys`. */
    val publicKey: String? = null,
    /** The host key the server presented, shown until the user confirms or declines it. */
    val hostKey: HostKeyInfo? = null,
    /** Where the passalong-server flow is, when a dialog is open. */
    val serverStep: ServerStep? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** The config was written; the choose screen can open. */
    val done: Boolean = false,
) {
    /** The port as a number, or null when it is not one from 1 to 65535. */
    val portNumber: Int?
        get() = port.trim().toIntOrNull()?.takeIf { it in 1..MAX_PORT }

    /** Whether *Connect* can be pressed: the kind's fields are filled and nothing is running. */
    val canConnect: Boolean
        get() =
            when (kind) {
                ServerKind.SSH ->
                    host.isNotBlank() && user.isNotBlank() && remotePath.isNotBlank() && deviceName.isNotBlank() &&
                        portNumber != null && publicKey != null && !busy
                ServerKind.SERVER -> url.isNotBlank() && deviceName.isNotBlank() && !busy
            }

    /** Whether the host key can be fetched: the SSH fields are filled and the port is valid. */
    val canFetch: Boolean
        get() = kind == ServerKind.SSH && canConnect

    private companion object {
        const val MAX_PORT = 65535
    }
}

/**
 * `passalong init` as the app runs it. For an SSH server: this device gets an identity, the
 * user fills in the server, the host key is fetched and its fingerprint confirmed, and only then
 * is the config written. For a passalong-server: the certificate is probed without a request,
 * its pin confirmed (or the system trusted when it vouches), the API key typed or the saved one
 * used, the server asked about the key and the workspace, a "not encrypted" claim confirmed,
 * and only then are the key and the config written. Declining anywhere writes nothing.
 *
 * One function per thing the user can do, as `init` has one prompt per answer; that is more
 * functions than detekt likes in one class, and splitting the flow would hide its order.
 */
@Suppress("TooManyFunctions")
class SetupController(
    private val scope: CoroutineScope,
    private val core: Core,
    private val files: AppFiles,
    private val hostname: String,
    private val options: SetupOptions = SetupOptions(),
) {
    private val io: CoroutineContext get() = options.io

    private val _state = MutableStateFlow(SetupState(deviceName = hostname))

    /** What the screen shows. */
    val state: StateFlow<SetupState> = _state

    /** The pin the user confirmed, or null to trust the system; only while the server flow runs. */
    private var chosenPin: String? = null

    /** The API key as typed, held in memory only until it is saved or the flow ends. */
    private var typedApiKey: String? = null

    /** The config text the server flow will write, rendered once the pin is settled. */
    private var serverConfig: String? = null

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

    fun kindChanged(value: ServerKind) = _state.update { it.copy(kind = value, hostKey = null, error = null) }

    fun hostChanged(value: String) = _state.update { it.copy(host = value, hostKey = null) }

    fun portChanged(value: String) = _state.update { it.copy(port = value, hostKey = null) }

    fun userChanged(value: String) = _state.update { it.copy(user = value) }

    fun remotePathChanged(value: String) = _state.update { it.copy(remotePath = value) }

    fun urlChanged(value: String) = _state.update { it.copy(url = value, serverStep = null) }

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

    /** *Connect*: fetches the SSH host key, or probes the passalong-server's certificate. */
    fun connect() {
        when (_state.value.kind) {
            ServerKind.SSH -> fetchHostKey()
            ServerKind.SERVER -> probeServer()
        }
    }

    /** Fetches the server's host key, to show its fingerprint. */
    fun fetchHostKey() {
        val current = _state.value
        val port = current.portNumber ?: return
        if (!current.canFetch) return
        _state.update { it.copy(busy = true, error = null, hostKey = null) }
        scope.launch {
            runCatching { core.fetchHostKey(current.host.trim(), port, options.hostKeyTimeoutSecs) }
                .onSuccess { found -> _state.update { it.copy(hostKey = found, busy = false) } }
                .onFailure { err -> failed(err) }
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
            }.onFailure { err -> failed(err, hostKey = null) }
        }
    }

    // --- A passalong-server, as `init`'s https flow ------------------------------------------

    /** Validates the URL by rendering the config, then probes the certificate. */
    private fun probeServer() {
        val current = _state.value
        if (!current.canConnect) return
        chosenPin = null
        typedApiKey = null
        serverConfig = null
        _state.update { it.copy(busy = true, error = null, serverStep = null) }
        scope.launch {
            runCatching {
                // As `init` does: the URL and the key file's place are checked before any connection.
                core.validateConfig(core.renderHttpsConfig(answers(tlsPin = null)), files.home.path, hostname)
                core.probeServer(current.url.trim())
            }.onSuccess { presented ->
                _state.update {
                    it.copy(serverStep = ServerStep.ConfirmPin(presented, ATTEMPTS, problem = null), busy = false)
                }
            }.onFailure { err -> failed(err) }
        }
    }

    /**
     * The user pasted the pin the server's operator gave them. It must be the one presented;
     * after [ATTEMPTS] wrong ones the flow stops and nothing is written.
     */
    fun pinTyped(text: String) {
        val step = _state.value.serverStep as? ServerStep.ConfirmPin ?: return
        val problem =
            runCatching { core.parseTlsPin(text) }.fold(
                onSuccess = {
                        pin ->
                    if (pin == step.presented.pin) null else "That is not the pin the server presented."
                },
                onFailure = { err -> err.rethrowCancellation().describe() },
            )
        if (problem == null) {
            chosenPin = step.presented.pin
            pinSettled()
            return
        }
        val left = step.attemptsLeft - 1
        if (left == 0) {
            cancelPin()
        } else {
            _state.update { it.copy(serverStep = step.copy(attemptsLeft = left, problem = problem)) }
        }
    }

    /** The system vouches for the certificate and the user goes without a pin. */
    fun trustSystem() {
        val step = _state.value.serverStep as? ServerStep.ConfirmPin ?: return
        if (!step.presented.trusted) return
        chosenPin = null
        pinSettled()
    }

    /** The user stops at the pin; nothing is written. */
    fun cancelPin() = stop("the server's pin was not confirmed; nothing was written")

    /** With the pin settled: a saved API key is used, otherwise one is asked for. */
    private fun pinSettled() {
        _state.update { it.copy(busy = true, serverStep = null) }
        scope.launch {
            val saved = runCatching { withContext(io) { core.hasApiKey(files.apiKey.path) } }
            saved.onFailure { err -> failed(err) }.onSuccess { has ->
                if (has) {
                    inspect(apiKey = null)
                } else {
                    _state.update {
                        it.copy(
                            serverStep = ServerStep.TypeApiKey(ATTEMPTS, problem = null),
                            busy = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * The user typed the API key. Its shape is checked before any request; after [ATTEMPTS]
     * unusable ones the flow stops and nothing is written.
     */
    fun apiKeyTyped(text: String) {
        val step = _state.value.serverStep as? ServerStep.TypeApiKey ?: return
        val problem = runCatching { core.parseApiKey(text) }.exceptionOrNull()?.rethrowCancellation()?.describe()
        if (problem == null) {
            typedApiKey = text.trim()
            _state.update { it.copy(busy = true, serverStep = null) }
            scope.launch { inspect(apiKey = text.trim()) }
            return
        }
        val left = step.attemptsLeft - 1
        if (left == 0) {
            stop("no usable API key was typed; nothing was written")
        } else {
            _state.update { it.copy(serverStep = step.copy(attemptsLeft = left, problem = problem)) }
        }
    }

    /** The user stops at the API key; nothing is written. */
    fun cancelApiKey() = stop("no API key was typed; nothing was written")

    /** Asks the server about the key and the workspace, then has a plaintext claim confirmed. */
    private suspend fun inspect(apiKey: String?) {
        val outcome =
            runCatching {
                val text = core.renderHttpsConfig(answers(chosenPin))
                core.validateConfig(text, files.home.path, hostname)
                val info = core.inspectServer(text, files.home.path, hostname, apiKey)
                val hasStoreKey = withContext(io) { core.deviceHasStoreKey(text, files.home.path, hostname) }
                Triple(text, info, hasStoreKey)
            }
        val (text, info, hasStoreKey) = outcome.getOrElse { err -> return failed(err) }
        serverConfig = text
        val summary = "connected to passalong-server ${info.serverVersion}: API key ${Formats.keySummary(
            info,
            options.now(),
        )}"
        // A server could claim "not encrypted" to have a new device send plaintext; only the
        // user can tell whether that is expected.
        if (info.encryptionState == "plaintext" && !hasStoreKey) {
            _state.update { it.copy(serverStep = ServerStep.ConfirmPlaintext(info, summary), busy = false) }
        } else {
            write()
        }
    }

    /** The user answered whether the workspace is meant to be unencrypted. */
    fun plaintextConfirmed(confirmed: Boolean) {
        if (_state.value.serverStep !is ServerStep.ConfirmPlaintext) return
        if (!confirmed) {
            stop("the server's claim was not confirmed; nothing was written. Ask the server's operator")
            return
        }
        _state.update { it.copy(busy = true, serverStep = null) }
        scope.launch { write() }
    }

    /** Saves the typed key, if one was, then the config. */
    private suspend fun write() {
        val text = serverConfig ?: return stop("nothing to write")
        val key = typedApiKey
        runCatching {
            withContext(io) {
                if (key != null) core.saveApiKey(files.apiKey.path, key)
                files.writeConfig(text)
            }
        }.onSuccess {
            typedApiKey = null
            _state.update { it.copy(serverStep = null, done = true, error = null, busy = false) }
        }.onFailure { err -> failed(err) }
    }

    private fun answers(tlsPin: String?): HttpsSetupAnswers {
        val current = _state.value
        return HttpsSetupAnswers(
            deviceName = current.deviceName.trim(),
            url = current.url.trim(),
            tlsPin = tlsPin,
            apiKeyFile = files.apiKey.path,
        )
    }

    private fun stop(message: String) {
        typedApiKey = null
        serverConfig = null
        _state.update { it.copy(serverStep = null, error = message, busy = false) }
    }

    private fun failed(
        err: Throwable,
        hostKey: HostKeyInfo? = _state.value.hostKey,
    ) {
        typedApiKey = null
        _state.update {
            it.copy(hostKey = hostKey, serverStep = null, error = err.rethrowCancellation().describe(), busy = false)
        }
    }

    private companion object {
        /** How often a pin or a key may be typed wrong, as the CLI allows. */
        const val ATTEMPTS = 3
    }
}
