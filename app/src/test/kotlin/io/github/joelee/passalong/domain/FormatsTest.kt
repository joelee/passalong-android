package io.github.joelee.passalong.domain

import io.github.joelee.passalong.support.Samples
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class FormatsTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `sizes are human readable as the CLI prints them`() {
        val cases =
            listOf(
                0L to "0 B",
                1023L to "1023 B",
                1024L to "1.0 KiB",
                1536L to "1.5 KiB",
                5L * 1024 * 1024 to "5.0 MiB",
                3L * 1024 * 1024 * 1024 to "3.0 GiB",
                -1L to "16777216.0 TiB",
            )
        for ((bytes, text) in cases) {
            assertEquals("$bytes", text, Formats.humanSize(bytes))
        }
    }

    @Test
    fun `times are shown in the given zone`() {
        assertEquals("2026-09-12 09:53", Formats.listTime(Samples.T0, utc))
        assertEquals("2026-09-12 11:53", Formats.listTime(Samples.T0, ZoneOffset.ofHours(2)))
        val item = Samples.file("report.pdf", 1536)
        assertEquals("file  ·  1.5 KiB  ·  laptop  ·  2026-09-12 09:53", Formats.rowDetails(item, utc))
    }

    @Test
    fun `metadata lines read as passalong get prints them`() {
        val file = Samples.file("report.pdf", 1536)
        val lines = Formats.metaLines(file, ZoneOffset.ofHours(2))
        assertEquals(
            listOf(
                "id:      ${file.id}",
                "kind:    file",
                "name:    report.pdf",
                "mime:    application/pdf",
                "size:    1.5 KiB (1536 bytes)",
                "sha256:  ${"cd".repeat(32)}",
                "device:  laptop",
                "created: 2026-09-12 11:53:11 +02:00 (2026-09-12T09:53:11Z)",
            ),
            lines,
        )
        val text = Formats.metaLines(Samples.text("hello"), utc)
        assertEquals("preview: hello", text[2])
        assertEquals("created: 2026-09-12 09:53:11 +00:00 (2026-09-12T09:53:11Z)", text.last())
        val image = Formats.metaLines(Samples.file("clipboard-20260912-095311.png", 3, mime = "image/png"), utc)
        assertEquals("kind:    image", image[1])
        assertEquals("origin:  clipboard", image[7])
        val bare = Formats.metaLines(Samples.text("x").copy(preview = null), utc)
        assertEquals("mime:    text/plain; charset=utf-8", bare[2])
    }
}
