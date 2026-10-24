package io.github.joelee.passalong.data

import java.io.File

/**
 * Where the app keeps the core's files, all under its private [home]: the config, this device's
 * identity, the list cache, and the download staging folder the core's `~/Downloads` expands to.
 */
class AppFiles(val home: File) {
    /** The config file, in the core's `config.toml` format. */
    val config: File = File(home, "config.toml")

    /** This device's private key; the public key is beside it as `.pub`. */
    val identity: File = File(home, "keys/id_ed25519")

    /** The public key file. */
    val publicKey: File = File(identity.path + ".pub")

    /** A passalong-server API key, owner-only, beside the config as `init` places it. */
    val apiKey: File = File(home, "api.key")

    /** The list cache, as `serve` keeps it on desktops. */
    val listCache: File = File(home, "list-cache.json")

    /** Whether the device has been set up. */
    fun hasConfig(): Boolean = config.isFile

    /** The config text, or null before setup. */
    fun readConfig(): String? = config.takeIf { it.isFile }?.readText()

    /** Writes the config, readable by the app only, replacing any earlier one. */
    fun writeConfig(text: String) {
        home.mkdirs()
        val temp = File(home, "config.toml.tmp")
        temp.writeText(text)
        if (!temp.renameTo(config)) {
            config.writeText(text)
            temp.delete()
        }
    }

    /** The public key line, or null before a key was generated. */
    fun readPublicKey(): String? = publicKey.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
}
