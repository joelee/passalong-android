package io.github.joelee.passalong.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text shown for sizes, times, and metadata, as the CLI prints them. */
object Formats {
    private const val KIB = 1024.0
    private val units = listOf("KiB", "MiB", "GiB", "TiB")
    private val listTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val localTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)
    private val utcTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)

    /** A byte count with binary units: `5 B`, `1.5 KiB`, `3.0 GiB`. */
    fun humanSize(bytes: Long): String {
        if (bytes in 0 until KIB.toLong()) return "$bytes B"
        var value = bytes.toULong().toDouble() / KIB
        var unit = 0
        while (value >= KIB && unit < units.size - 1) {
            value /= KIB
            unit += 1
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    /** The creation time as `passalong list` shows it, in [zone]. */
    fun listTime(
        epochSeconds: Long,
        zone: ZoneId,
    ): String = listTime.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

    /** The row's second line: kind, size, device, and time. */
    fun rowDetails(
        item: Item,
        zone: ZoneId,
    ): String =
        listOf(item.kind, humanSize(item.size), item.device, listTime(item.createdAt, zone)).joinToString("  ·  ")

    /**
     * One item's metadata as aligned `field: value` lines, as `passalong get` prints them: the name
     * for files or the preview for text, the origin for clipboard images, and the creation time in
     * [zone] beside UTC.
     */
    fun metaLines(
        item: Item,
        zone: ZoneId,
    ): List<String> {
        val instant = Instant.ofEpochSecond(item.createdAt)
        val fields = mutableListOf("id" to item.id, "kind" to item.kind)
        when {
            item.name != null -> fields += "name" to item.name
            item.preview != null -> fields += "preview" to item.preview
        }
        fields += "mime" to item.mime
        fields += "size" to "${humanSize(item.size)} (${item.size} bytes)"
        fields += "sha256" to item.sha256
        fields += "device" to item.device
        if (item.kind == "image") fields += "origin" to "clipboard"
        val local = localTime.format(instant.atZone(zone))
        val utc = utcTime.format(instant.atZone(ZoneOffset.UTC))
        fields += "created" to "$local ($utc)"
        return fields.map { (label, value) -> "$label:".padEnd(LABEL_WIDTH) + value }
    }

    private const val LABEL_WIDTH = 9
}
