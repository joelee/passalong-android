package io.github.joelee.passalong.data

import io.github.joelee.passalong.core.StoreHandle
import io.github.joelee.passalong.domain.Item
import java.io.File

/** Where loaded text goes. */
fun interface ClipboardSink {
    /** Puts [text] on the clipboard. */
    fun setText(text: String)
}

/** Where loaded files go. */
fun interface DownloadsSink {
    /**
     * Publishes the verified file at [path] to the device's downloads under [name] with [mime], and
     * returns the name it was given, which differs when [name] was taken.
     */
    fun publish(
        path: String,
        name: String,
        mime: String,
    ): String
}

/** What loading an item did. */
sealed interface Loaded {
    /** The text is on the clipboard. */
    data object Clipboard : Loaded

    /** The file is in the downloads under [name]. */
    data class Downloaded(val name: String) : Loaded
}

/** The actions of `passalong choose` that leave the list: `load` and `cat`. */
class ItemActions(
    private val store: StoreHandle,
    private val clipboard: ClipboardSink,
    private val downloads: DownloadsSink,
) {
    /**
     * As `passalong load <ID>`: text goes to the clipboard; files and clipboard images are
     * downloaded, verified, into the app's staging folder and then published to the downloads.
     * The staged copy is removed once published.
     */
    suspend fun load(item: Item): Loaded {
        if (item.isText) {
            clipboard.setText(store.readText(item.id))
            return Loaded.Clipboard
        }
        val path = store.download(item.id, null)
        val staged = File(path)
        try {
            return Loaded.Downloaded(downloads.publish(path, staged.name, item.mime))
        } finally {
            staged.delete()
        }
    }

    /** As `passalong cat <ID>`: the item's text, verified; binary items are refused by the core. */
    suspend fun cat(item: Item): String = store.readText(item.id)
}
