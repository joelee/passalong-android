package io.github.joelee.passalong.data

import io.github.joelee.passalong.core.CoreException
import io.github.joelee.passalong.support.FakeClipboard
import io.github.joelee.passalong.support.FakeDownloads
import io.github.joelee.passalong.support.FakeStore
import io.github.joelee.passalong.support.Samples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ItemActionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val text = Samples.text("hello")
    private val file = Samples.file("report.pdf", 4)
    private val store = FakeStore(listOf(file, text)).apply { texts[text.id] = "hello" }
    private val clipboard = FakeClipboard()
    private val downloads = FakeDownloads()
    private val actions = ItemActions(store, clipboard, downloads, Dispatchers.Unconfined)

    @Test
    fun `loading text puts it on the clipboard`() =
        runTest {
            assertEquals(Loaded.Clipboard, actions.load(text))
            assertEquals(listOf("hello"), clipboard.texts)
            assertEquals("hello", actions.cat(text))
        }

    @Test
    fun `loading a file downloads it, publishes it, and removes the staged copy`() =
        runTest {
            store.downloadDir = temp.newFolder("Downloads")
            assertEquals(Loaded.Downloaded("report.pdf"), actions.load(file))
            val (path, name, mime) = downloads.published.single()
            assertEquals(File(store.downloadDir, "report.pdf").path, path)
            assertEquals("report.pdf", name)
            assertEquals("application/pdf", mime)
            assertFalse("staged copy removed", File(path).exists())
            assertEquals(Loaded.Downloaded("report (1).pdf"), actions.load(file))
        }

    @Test
    fun `a failed publish still removes the staged copy`() =
        runTest {
            store.downloadDir = temp.newFolder("Downloads")
            downloads.fail = IllegalStateException("no downloads")
            assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { actions.load(file) } }
            assertFalse(File(store.downloadDir, "report.pdf").exists())
        }

    @Test
    fun `cat refuses binary items as the core does`() =
        runTest {
            val err = assertThrows(CoreException::class.java) { kotlinx.coroutines.runBlocking { actions.cat(file) } }
            assertEquals(CoreException.Kind.STORE, err.kind)
        }
}
