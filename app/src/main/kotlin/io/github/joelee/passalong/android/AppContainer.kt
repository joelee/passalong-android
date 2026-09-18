package io.github.joelee.passalong.android

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.joelee.passalong.BuildConfig
import io.github.joelee.passalong.choose.ActionTargets
import io.github.joelee.passalong.choose.ChooseController
import io.github.joelee.passalong.core.Core
import io.github.joelee.passalong.data.AppFiles
import io.github.joelee.passalong.data.ClipboardSink
import io.github.joelee.passalong.data.DownloadsSink
import io.github.joelee.passalong.setup.SetupController

/** The app's process: installs the core's logging before anything else runs. */
class PassalongApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FfiCore.initLogging(if (BuildConfig.DEBUG) "debug" else "info")
    }
}

/** What the screens need, built once per process. */
class AppContainer(context: Context) {
    val core: Core = FfiCore()
    val files: AppFiles = AppFiles(context.filesDir)
    val clipboard: ClipboardSink = AndroidClipboard(context)
    val downloads: DownloadsSink = MediaStoreDownloads(context)

    /** The default device name: the model, as `init` defaults to the host name. */
    val hostname: String = deviceName(Build.MODEL)

    /** Builds the screens' view models. */
    val viewModels: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T =
                when (modelClass) {
                    ChooseViewModel::class.java -> ChooseViewModel(this@AppContainer) as T
                    SetupViewModel::class.java -> SetupViewModel(this@AppContainer) as T
                    else -> error("unknown view model $modelClass")
                }
        }

    companion object {
        /** A device name from the model: letters, digits, `-`, `_`, and `.`, or `android`. */
        fun deviceName(model: String): String {
            val cleaned = model.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
            return cleaned.ifEmpty { "android" }
        }
    }
}

/** Keeps the choose controller, and its store connection, across configuration changes. */
class ChooseViewModel(container: AppContainer) : ViewModel() {
    val controller =
        ChooseController(
            viewModelScope,
            container.core,
            container.files,
            container.hostname,
            ActionTargets(container.clipboard, container.downloads),
        ).also { it.connect() }
}

/** Keeps the setup controller across configuration changes. */
class SetupViewModel(container: AppContainer) : ViewModel() {
    val controller = SetupController(viewModelScope, container.core, container.files, container.hostname)
}
