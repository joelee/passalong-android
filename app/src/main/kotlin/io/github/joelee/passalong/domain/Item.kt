package io.github.joelee.passalong.domain

/**
 * One stored item's metadata, as the core's `meta.json` describes it.
 *
 * @property id the item id, `<time>-<content key>`
 * @property kind `text`, `file`, or `image` for a clipboard image, as the CLI labels it
 * @property name the file name, null for text
 * @property mime the MIME type
 * @property size the content length in bytes
 * @property sha256 the SHA-256 of the content, hex
 * @property createdAt the creation time, seconds since the Unix epoch
 * @property device the sending device
 * @property preview the first 80 characters of a text item, null for files
 */
data class Item(
    val id: String,
    val kind: String,
    val name: String?,
    val mime: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val device: String,
    val preview: String?,
) {
    /** The file name for files, the preview for text, or `-`, as `passalong list` shows it. */
    val displayName: String
        get() = name ?: preview ?: "-"

    /** Whether the item is text, so it goes to the clipboard when loaded and can be shown. */
    val isText: Boolean
        get() = kind == "text"

    /** The content key, the part of the id after the `-`, which users may type instead of the id. */
    val contentKey: String
        get() = id.substringAfter('-')

    /**
     * Whether the lower-case [needle] is in the id, the name or preview, the device, or the kind,
     * ignoring case, as the TUI's filter matches.
     */
    fun matches(needle: String): Boolean {
        if (needle.isEmpty()) return true
        val fields = listOf(id, name ?: preview ?: "", device, kind)
        return fields.any { it.lowercase().contains(needle) }
    }
}
