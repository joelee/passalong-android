package io.github.joelee.passalong.support

import io.github.joelee.passalong.core.CachedItems
import io.github.joelee.passalong.core.ConfigInfo
import io.github.joelee.passalong.core.Core
import io.github.joelee.passalong.core.CoreException
import io.github.joelee.passalong.core.HostKeyInfo
import io.github.joelee.passalong.core.HttpsSetupAnswers
import io.github.joelee.passalong.core.PresentedCertificate
import io.github.joelee.passalong.core.ServerInfo
import io.github.joelee.passalong.core.SetupAnswers
import io.github.joelee.passalong.core.StoreHandle
import io.github.joelee.passalong.data.ClipboardSink
import io.github.joelee.passalong.data.DownloadsSink
import io.github.joelee.passalong.domain.Item
import java.io.File

/** Sample items, newest first, as a store from device `box` would list them. */
object Samples {
    /** Whole seconds since the epoch of 2026-09-12T09:53:11Z. */
    const val T0 = 1_789_206_791L

    fun text(
        text: String,
        secondsAfterT0: Long = 0,
        device: String = "box",
    ): Item {
        val ts = T0 + secondsAfterT0
        val key = text.hashCode().toUInt().toString(16).padStart(12, 'a').take(12)
        return Item(
            id = "${ts.toString(16)}-$key",
            kind = "text",
            name = null,
            mime = "text/plain; charset=utf-8",
            size = text.toByteArray().size.toLong(),
            sha256 = "ab".repeat(32),
            createdAt = ts,
            device = device,
            preview = text.take(80),
        )
    }

    fun file(
        name: String,
        size: Long,
        secondsAfterT0: Long = 0,
        mime: String = "application/pdf",
    ): Item {
        val ts = T0 + secondsAfterT0
        return Item(
            id = "${ts.toString(16)}-${name.hashCode().toUInt().toString(16).padStart(12, 'b').take(12)}",
            kind = if (name.startsWith("clipboard-")) "image" else "file",
            name = name,
            mime = mime,
            size = size,
            sha256 = "cd".repeat(32),
            createdAt = ts,
            device = "laptop",
            preview = null,
        )
    }

    /** `texts` in the given order, oldest last, one second apart. */
    fun texts(vararg texts: String): List<Item> = texts.mapIndexed { i, t -> text(t, (texts.size - i).toLong()) }
}

/** A store in memory whose items and failures tests control. */
class FakeStore(items: List<Item> = emptyList()) : StoreHandle {
    val items: MutableList<Item> = items.toMutableList()
    val texts: MutableMap<String, String> = mutableMapOf()
    var cache: CachedItems? = null
    var failList: Exception? = null
    var failDelete: Exception? = null
    var failRead: Exception? = null
    var failMeta: Exception? = null
    val calls: MutableList<String> = mutableListOf()
    override val description: String = "ssh u@h:22 /srv/passalong"
    var downloadDir: File? = null

    /** Runs at the start of every call that reads or changes the store, as the TUI draws a frame. */
    var onCall: () -> Unit = {}

    override fun cached(): CachedItems? = cache.also { calls += "cached" }

    override suspend fun list(reuse: Boolean): List<Item> {
        calls += "list($reuse)"
        onCall()
        failList?.let { throw it }
        return items.sortedByDescending { it.id }
    }

    override suspend fun getMeta(id: String): Item {
        calls += "getMeta($id)"
        failMeta?.let { throw it }
        return items.firstOrNull { it.id == id }
            ?: throw CoreException(CoreException.Kind.NOT_FOUND, "no item matches `$id`")
    }

    override suspend fun readText(id: String): String {
        calls += "readText($id)"
        failRead?.let { throw it }
        val item = getMeta(id)
        if (item.kind != "text" && !item.mime.startsWith("text/")) {
            throw CoreException(CoreException.Kind.STORE, "item $id is binary (${item.mime}); load it instead")
        }
        return texts[id] ?: item.preview.orEmpty()
    }

    override suspend fun download(
        id: String,
        dir: String?,
    ): String {
        calls += "download($id, $dir)"
        val item = getMeta(id)
        val target = File(dir?.let(::File) ?: checkNotNull(downloadDir), item.name ?: "$id.txt")
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(item.size.toInt()))
        return target.path
    }

    override suspend fun delete(id: String): Item {
        calls += "delete($id)"
        onCall()
        failDelete?.let { throw it }
        val item = getMeta(id)
        items.remove(item)
        return item
    }
}

/** A core whose store, host key, and failures tests control. */
class FakeCore(var store: FakeStore = FakeStore()) : Core {
    var failOpen: CoreException? = null
    var hostKey: HostKeyInfo = HostKeyInfo("ssh-ed25519 AAAAtest", "SHA256:fp", "ssh-ed25519")
    var failFetch: CoreException? = null
    var presented: PresentedCertificate = PresentedCertificate("sha256/AAAA", trusted = false)
    var failProbe: CoreException? = null
    var failGenerate: CoreException? = null
    var failValidate: CoreException? = null
    val calls: MutableList<String> = mutableListOf()
    var generated = 0

    override fun coreVersion(): String = "0.2.0"

    override suspend fun fetchHostKey(
        host: String,
        port: Int,
        timeoutSecs: Long,
    ): HostKeyInfo {
        calls += "fetchHostKey($host, $port, $timeoutSecs)"
        failFetch?.let { throw it }
        return hostKey
    }

    override suspend fun probeServer(url: String): PresentedCertificate {
        calls += "probeServer($url)"
        failProbe?.let { throw it }
        return presented
    }

    override fun generateIdentity(
        path: String,
        comment: String,
        replace: Boolean,
    ): String {
        calls += "generateIdentity($path, $comment, $replace)"
        failGenerate?.let { throw it }
        val file = File(path)
        if (file.exists() && !replace) throw CoreException(CoreException.Kind.IO, "$path already exists")
        generated += 1
        file.parentFile?.mkdirs()
        file.writeText("PRIVATE $generated")
        val public = "ssh-ed25519 AAAAgenerated$generated $comment"
        File("$path.pub").writeText("$public\n")
        return public
    }

    override fun renderConfig(answers: SetupAnswers): String =
        """
        [client]
        device_name = "${answers.deviceName}"

        [server]
        kind = "ssh"

        [server.ssh]
        host = "${answers.host}"
        port = ${answers.port}
        user = "${answers.user}"
        host_key = "${answers.hostKey}"
        identity_file = "${answers.identityFile}"
        remote_path = "${answers.remotePath}"
        """.trimIndent() + "\n"

    override fun validateConfig(
        configToml: String,
        home: String,
        hostname: String,
    ): ConfigInfo {
        calls += "validateConfig"
        failValidate?.let { throw it }
        if (configToml.contains("url = \"http://")) {
            throw CoreException(CoreException.Kind.CONFIG, "server.https.url: plain http:// is refused")
        }
        val kind = if (configToml.contains("kind = \"https\"")) "https" else "ssh"
        return ConfigInfo("d", kind, "h", 22, "u", "/r", "$home/keys/id_ed25519", "$home/Downloads", null)
    }

    var serverInfo: ServerInfo =
        ServerInfo(
            serverVersion = "0.1.0",
            apiVersion = 1,
            sourceUrl = "https://github.com/joelee/passalong-server",
            keyId = "0123abcd",
            keyLabel = "phone",
            keyRole = "read-write",
            keyExpiresAt = null,
            workspace = "home",
            itemCount = 3,
            quotaBytes = 20L shl 30,
            usedBytes = 1536,
            encryptionState = "plaintext",
            encryptionKeyId = null,
        )
    var failInspect: CoreException? = null
    var hasStoreKey = false
    val savedKeys: MutableList<String> = mutableListOf()

    override fun parseTlsPin(text: String): String {
        val trimmed = text.trim().replaceFirst("sha256//", "sha256/")
        val ok = Regex("""sha256/[A-Za-z0-9+/]{43}=""").matches(trimmed)
        if (!ok) throw CoreException(CoreException.Kind.CONFIG, "a pin is `sha256/` and 32 bytes in base64")
        return trimmed
    }

    override fun parseApiKey(text: String): String =
        Regex("""pal_([A-Za-z0-9-]+)_[A-Za-z0-9_-]+""").matchEntire(text.trim())?.groupValues?.get(1)
            ?: throw CoreException(CoreException.Kind.CONFIG, "an API key is `pal_<key id>_<secret>`")

    override fun hasApiKey(path: String): Boolean = File(path).isFile

    override fun saveApiKey(
        path: String,
        key: String,
    ) {
        calls += "saveApiKey"
        File(path).parentFile?.mkdirs()
        File(path).writeText(key + "\n")
        savedKeys += key
    }

    override fun renderHttpsConfig(answers: HttpsSetupAnswers): String =
        """
        [client]
        device_name = "${answers.deviceName}"

        [server]
        kind = "https"

        [server.https]
        url = "${answers.url}"
        ${answers.tlsPin?.let { "tls_pin = \"$it\"" } ?: "# No tls_pin"}
        api_key_file = "${answers.apiKeyFile}"
        """.trimIndent() + "\n"

    override suspend fun inspectServer(
        configToml: String,
        home: String,
        hostname: String,
        apiKey: String?,
    ): ServerInfo {
        calls += "inspectServer(${apiKey?.let { "typed" } ?: "saved"})"
        failInspect?.let { throw it }
        return serverInfo
    }

    override fun deviceHasStoreKey(
        configToml: String,
        home: String,
        hostname: String,
    ): Boolean = hasStoreKey

    override suspend fun openStore(
        configToml: String,
        home: String,
        hostname: String,
        cacheFile: String?,
    ): StoreHandle {
        calls += "openStore($home, $hostname, $cacheFile)"
        failOpen?.let { throw it }
        return store
    }
}

class FakeClipboard : ClipboardSink {
    val texts = mutableListOf<String>()

    override fun setText(text: String) {
        texts += text
    }
}

class FakeDownloads : DownloadsSink {
    val published = mutableListOf<Triple<String, String, String>>()
    var fail: Exception? = null

    override fun publish(
        path: String,
        name: String,
        mime: String,
    ): String {
        fail?.let { throw it }
        published += Triple(path, name, mime)
        return if (published.count { it.second == name } > 1) name.replace(".", " (1).") else name
    }
}
