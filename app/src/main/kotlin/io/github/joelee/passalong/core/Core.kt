package io.github.joelee.passalong.core

import io.github.joelee.passalong.domain.Item

/** A server host key seen during discovery, not yet trusted. */
data class HostKeyInfo(
    /** The key as an OpenSSH `type base64` line, ready to pin. */
    val opensshLine: String,
    /** The SHA-256 fingerprint, as `ssh-keygen -l` prints it. */
    val fingerprint: String,
    /** The algorithm name, such as `ssh-ed25519`. */
    val algorithm: String,
)

/**
 * The certificate a passalong-server presents, seen with a handshake that stops before any
 * request: its public key's pin, `sha256/<base64>`, and whether the system trusts it.
 */
data class PresentedCertificate(val pin: String, val trusted: Boolean)

/** The values the setup screen collects, as `passalong init` asks them. */
data class SetupAnswers(
    val deviceName: String,
    val host: String,
    val port: Int,
    val user: String,
    val hostKey: String,
    val identityFile: String,
    val remotePath: String,
)

/** The values the setup screen collects for a passalong-server, as `passalong init` asks them. */
data class HttpsSetupAnswers(
    val deviceName: String,
    val url: String,
    /** The confirmed pin, or null to trust the operating system's certificate authorities. */
    val tlsPin: String?,
    val apiKeyFile: String,
)

/**
 * What a passalong-server says about itself, the API key in use, and the workspace that key
 * opens, as `init` asks before writing anything and `check` reports.
 */
data class ServerInfo(
    val serverVersion: String,
    val apiVersion: Int,
    val sourceUrl: String?,
    val keyId: String,
    val keyLabel: String?,
    /** `read-write`, `read-only`, or `unknown`. */
    val keyRole: String,
    /** When the key expires, seconds since the Unix epoch; null for never. */
    val keyExpiresAt: Long?,
    val workspace: String,
    val itemCount: Long,
    val quotaBytes: Long,
    val usedBytes: Long,
    /** `plaintext`, `sealed`, `rewriting`, or `unknown`. */
    val encryptionState: String,
    val encryptionKeyId: String?,
)

/** What a valid config says. */
data class ConfigInfo(
    val deviceName: String,
    val serverKind: String,
    val host: String,
    val port: Int,
    val user: String,
    val remotePath: String,
    val identityFile: String,
    val downloadDir: String,
    val keyFile: String?,
)

/** The items a fresh list cache holds, and how old it is in seconds. */
data class CachedItems(val items: List<Item>, val ageSecs: Long)

/** What went wrong in the core, grouped by what the app can do about it. */
class CoreException(val kind: Kind, message: String) : Exception(message) {
    /** The groups the bridge's error type has. */
    enum class Kind {
        /** The configuration is missing, invalid, or names something unusable. */
        CONFIG,

        /** The server cannot be reached, refuses the login, or presents another host key. */
        CONNECT,

        /** No item has this id (any more). */
        NOT_FOUND,

        /** The store is encrypted and this device cannot open it. */
        ENCRYPTION,

        /** A passalong-server refused this device for good: its API key is wrong, expired, or revoked. */
        DENIED,

        /** Any other storage problem. */
        STORE,

        /** A file on this device cannot be read or written. */
        IO,
    }
}

/**
 * What setting up a passalong-server needs from the core, as `init`'s https flow calls it. Every
 * function may throw [CoreException].
 */
interface ServerSetup {
    /** Fetches the certificate the passalong-server at [url] presents, without sending a request. */
    suspend fun probeServer(url: String): PresentedCertificate

    /** Reads a pin in either written form and returns it canonical, `sha256/<base64>`. */
    fun parseTlsPin(text: String): String

    /** Checks the shape of an API key, `pal_<id>_<secret>`, and returns its public id. */
    fun parseApiKey(text: String): String

    /** Whether an API key is saved at [path]. */
    fun hasApiKey(path: String): Boolean

    /** Saves the API key at [path], readable by the app alone. */
    fun saveApiKey(
        path: String,
        key: String,
    )

    /** Renders the config file `passalong init` writes for a passalong-server. */
    fun renderHttpsConfig(answers: HttpsSetupAnswers): String

    /**
     * Asks the server the config names about the key and the workspace, with [apiKey] as typed or
     * the saved one; nothing is written.
     */
    suspend fun inspectServer(
        configToml: String,
        home: String,
        hostname: String,
        apiKey: String?,
    ): ServerInfo

    /** Whether this device holds a store key, so a "not encrypted" claim needs no confirmation. */
    fun deviceHasStoreKey(
        configToml: String,
        home: String,
        hostname: String,
    ): Boolean
}

/**
 * The passalong core as the app uses it. [io.github.joelee.passalong.android.FfiCore] is the real
 * one over the Rust bridge; tests use a fake. Every function may throw [CoreException].
 */
interface Core : ServerSetup {
    /** The version of the passalong core. */
    fun coreVersion(): String

    /** Fetches the host key `host:port` presents, without trusting it. */
    suspend fun fetchHostKey(
        host: String,
        port: Int,
        timeoutSecs: Long,
    ): HostKeyInfo

    /**
     * Writes an ed25519 identity to [path] and `<path>.pub`, and returns the public key line. An
     * existing key is kept unless [replace] is set.
     */
    fun generateIdentity(
        path: String,
        comment: String,
        replace: Boolean,
    ): String

    /** Renders the config file `passalong init` writes. */
    fun renderConfig(answers: SetupAnswers): String

    /** Parses and validates a config, with [home] as `~` and [hostname] as the default device name. */
    fun validateConfig(
        configToml: String,
        home: String,
        hostname: String,
    ): ConfigInfo

    /** Connects and opens the store the config names; the list cache is kept in [cacheFile]. */
    suspend fun openStore(
        configToml: String,
        home: String,
        hostname: String,
        cacheFile: String?,
    ): StoreHandle
}

/** An open store, with what `passalong choose` does. Every function may throw [CoreException]. */
interface StoreHandle {
    /** Where the items are, such as `ssh user@host:22 /srv/passalong`. */
    val description: String

    /** The list cache when it is fresh enough to show instead of the store. */
    fun cached(): CachedItems?

    /** Every item, newest first; with [reuse] the cache is refreshed, otherwise read again. */
    suspend fun list(reuse: Boolean): List<Item>

    /** One item's metadata. */
    suspend fun getMeta(id: String): Item

    /** A text item's content, verified. */
    suspend fun readText(id: String): String

    /** Downloads an item into [dir], or the config's download directory, and returns the path. */
    suspend fun download(
        id: String,
        dir: String?,
    ): String

    /** Deletes an item and returns its metadata. */
    suspend fun delete(id: String): Item
}
