package io.github.joelee.passalong.domain

import io.github.joelee.passalong.support.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemTest {
    @Test
    fun `display name is the file name, the preview, or a dash`() {
        assertEquals("report.pdf", Samples.file("report.pdf", 10).displayName)
        assertEquals("hello", Samples.text("hello").displayName)
        assertEquals("-", Samples.text("hello").copy(preview = null).displayName)
    }

    @Test
    fun `text items are text and their content key follows the dash`() {
        val text = Samples.text("hello")
        assertTrue(text.isText)
        assertFalse(Samples.file("a.pdf", 1).isText)
        assertEquals(text.id.substringAfter('-'), text.contentKey)
        assertEquals(12, text.contentKey.length)
    }

    @Test
    fun `the filter matches id, name or preview, device, and kind, ignoring case`() {
        val text = Samples.text("Alpha one", device = "Box")
        val file = Samples.file("Report.pdf", 5)
        assertTrue(text.matches(""))
        assertTrue(text.matches("alpha"))
        assertFalse(text.matches("report"))
        assertTrue(file.matches("report"))
        assertTrue(text.matches("box"))
        assertTrue(text.matches("text"))
        assertTrue(file.matches("file"))
        assertTrue(file.matches("laptop"))
        assertTrue(text.matches(text.id.take(6)))
        assertFalse(text.matches("nothing matches"))
    }
}
