package io.github.joelee.passalong.android

import io.github.joelee.passalong.core.CachedItems
import io.github.joelee.passalong.core.ConfigInfo
import io.github.joelee.passalong.core.Core
import io.github.joelee.passalong.core.CoreException
import io.github.joelee.passalong.core.HostKeyInfo
import io.github.joelee.passalong.core.SetupAnswers
import io.github.joelee.passalong.core.StoreHandle
import io.github.joelee.passalong.domain.Item
import io.github.joelee.passalong.ffi.PassalongException
import io.github.joelee.passalong.ffi.PassalongStore
import io.github.joelee.passalong.ffi.Item as FfiItem
import io.github.joelee.passalong.ffi.SetupAnswers as FfiSetupAnswers
import io.github.joelee.passalong.ffi.coreVersion as ffiCoreVersion
import io.github.joelee.passalong.ffi.fetchHostKey as ffiFetchHostKey
import io.github.joelee.passalong.ffi.generateIdentity as ffiGenerateIdentity
import io.github.joelee.passalong.ffi.initLogging as ffiInitLogging
import io.github.joelee.passalong.ffi.openStore as ffiOpenStore
import io.github.joelee.passalong.ffi.renderConfig as ffiRenderConfig
import io.github.joelee.passalong.ffi.validateConfig as ffiValidateConfig

/**
 * The core over the Rust bridge. This file and nothing else touches the generated UniFFI
 * bindings; every bridge error becomes a [CoreException].
 */
class FfiCore : Core {
    override fun coreVersion(): String = ffiCoreVersion()

    override suspend fun fetchHostKey(
        host: String,
        port: Int,
        timeoutSecs: Long,
    ): HostKeyInfo =
        bridged {
            val found = ffiFetchHostKey(host, port.toUShort(), timeoutSecs.toULong())
            HostKeyInfo(found.opensshLine, found.fingerprint, found.algorithm)
        }

    override fun generateIdentity(
        path: String,
        comment: String,
        replace: Boolean,
    ): String = bridgedSync { ffiGenerateIdentity(path, comment, replace) }

    override fun renderConfig(answers: SetupAnswers): String =
        ffiRenderConfig(
            FfiSetupAnswers(
                deviceName = answers.deviceName,
                host = answers.host,
                port = answers.port.toUShort(),
                user = answers.user,
                hostKey = answers.hostKey,
                identityFile = answers.identityFile,
                remotePath = answers.remotePath,
            ),
        )

    override fun validateConfig(
        configToml: String,
        home: String,
        hostname: String,
    ): ConfigInfo =
        bridgedSync {
            val summary = ffiValidateConfig(configToml, home, hostname)
            ConfigInfo(
                deviceName = summary.deviceName,
                serverKind = summary.serverKind,
                host = summary.host,
                port = summary.port.toInt(),
                user = summary.user,
                remotePath = summary.remotePath,
                identityFile = summary.identityFile,
                downloadDir = summary.downloadDir,
                keyFile = summary.keyFile,
            )
        }

    override suspend fun openStore(
        configToml: String,
        home: String,
        hostname: String,
        cacheFile: String?,
    ): StoreHandle = bridged { FfiStore(ffiOpenStore(configToml, home, hostname, cacheFile)) }

    companion object {
        /** Installs the core's logging once per process; [level] as `client.log_level` names it. */
        fun initLogging(level: String) = ffiInitLogging(level)
    }
}

/** A store handle over the bridge's object. */
private class FfiStore(private val store: PassalongStore) : StoreHandle {
    override val description: String = store.description()

    override fun cached(): CachedItems? = store.cached()?.let { CachedItems(it.items.map(FfiItem::toItem), it.ageSecs) }

    override suspend fun list(reuse: Boolean): List<Item> = bridged { store.list(reuse).map(FfiItem::toItem) }

    override suspend fun getMeta(id: String): Item = bridged { store.getMeta(id).toItem() }

    override suspend fun readText(id: String): String = bridged { store.readText(id) }

    override suspend fun download(
        id: String,
        dir: String?,
    ): String = bridged { store.download(id, dir) }

    override suspend fun delete(id: String): Item = bridged { store.delete(id).toItem() }
}

private fun FfiItem.toItem(): Item =
    Item(
        id = id,
        kind = kind,
        name = name,
        mime = mime,
        size = size.toLong(),
        sha256 = sha256,
        createdAt = createdAt,
        device = device,
        preview = preview,
    )

private fun PassalongException.toCore(): CoreException {
    val kind =
        when (this) {
            is PassalongException.Config -> CoreException.Kind.CONFIG
            is PassalongException.Connect -> CoreException.Kind.CONNECT
            is PassalongException.NotFound -> CoreException.Kind.NOT_FOUND
            is PassalongException.Encryption -> CoreException.Kind.ENCRYPTION
            is PassalongException.Denied -> CoreException.Kind.DENIED
            is PassalongException.Store -> CoreException.Kind.STORE
            is PassalongException.Io -> CoreException.Kind.IO
        }
    return CoreException(kind, message.orEmpty())
}

private suspend fun <T> bridged(block: suspend () -> T): T =
    try {
        block()
    } catch (err: PassalongException) {
        throw err.toCore()
    }

private fun <T> bridgedSync(block: () -> T): T =
    try {
        block()
    } catch (err: PassalongException) {
        throw err.toCore()
    }
