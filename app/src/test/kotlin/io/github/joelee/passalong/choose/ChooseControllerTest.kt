package io.github.joelee.passalong.choose

import io.github.joelee.passalong.core.CachedItems
import io.github.joelee.passalong.core.CoreException
import io.github.joelee.passalong.data.AppFiles
import io.github.joelee.passalong.domain.Mode
import io.github.joelee.passalong.domain.PickerEvent
import io.github.joelee.passalong.support.FakeClipboard
import io.github.joelee.passalong.support.FakeCore
import io.github.joelee.passalong.support.FakeDownloads
import io.github.joelee.passalong.support.FakeStore
import io.github.joelee.passalong.support.Samples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Mirrors the TUI's `run_picker` tests: the statuses each step shows. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChooseControllerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val items = Samples.texts("newer", "older")
    private val store = FakeStore(items)
    private val core = FakeCore(store)
    private val clipboard = FakeClipboard()
    private val downloads = FakeDownloads()
    private lateinit var files: AppFiles

    /**
     * The status the list showed each time the store was read or changed, as the TUI's fake
     * screen records the frame drawn before each key; the final status is checked separately.
     */
    private val statuses = mutableListOf<String?>()
    private lateinit var current: ChooseController

    private fun TestScope.controller(withConfig: Boolean = true): ChooseController {
        files = AppFiles(temp.newFolder())
        store.downloadDir = files.home.resolve("Downloads")
        if (withConfig) files.writeConfig("[server]\nkind = \"ssh\"\n")
        val targets = ActionTargets(clipboard, downloads, Dispatchers.Unconfined, ZoneOffset.UTC)
        store.onCall = { statuses += current.state.value.picker.status }
        current = ChooseController(this, core, files, "pixel", targets)
        backgroundScope.launch { current.state.toList(mutableListOf()) }
        current.connect()
        return current
    }

    private fun runUnconfined(block: suspend TestScope.() -> Unit) =
        runTest(
            UnconfinedTestDispatcher(),
            testBody = block,
        )

    @Test
    fun `a fresh cache opens the list with its age and reload shows the cache again`() =
        runUnconfined {
            // The cache misses the newest item, so the count tells the source.
            store.cache = CachedItems(items.drop(1), 30)
            val controller = controller()
            val state = controller.state.value
            assertEquals(Connection.Ready("ssh u@h:22 /srv/passalong"), state.connection)
            assertEquals("cached 30 s ago", state.picker.status)
            assertEquals(1, state.picker.total)
            assertFalse(state.busy)
            controller.on(PickerEvent.Reload)
            assertEquals("reloaded: 1 items, cached 30 s ago", controller.state.value.picker.status)
            assertEquals(listOf("cached", "cached"), store.calls)
        }

    @Test
    fun `reading from the server refreshes the list and an old cache shows loading first`() =
        runUnconfined {
            store.cache = CachedItems(items.drop(1), 30)
            val controller = controller()
            controller.on(PickerEvent.ReloadServer)
            assertEquals(listOf("Reloading..."), statuses)
            assertEquals("reloaded: 2 items", controller.state.value.picker.status)
            assertEquals(2, controller.state.value.picker.total)
            assertEquals("list(false)", store.calls.last())
            // `r` without a fresh cache reads the store with reuse.
            store.cache = null
            controller.on(PickerEvent.Reload)
            assertEquals("reloaded: 2 items", controller.state.value.picker.status)
            assertEquals("list(true)", store.calls.last())
        }

    @Test
    fun `without a cache the list loads from the store and tapping loads text to the clipboard`() =
        runUnconfined {
            val controller = controller()
            assertEquals(listOf("Loading..."), statuses)
            assertNull(controller.state.value.picker.status)
            assertEquals(2, controller.state.value.picker.total)
            store.texts[items[1].id] = "older text"
            controller.on(PickerEvent.Select(1))
            controller.on(PickerEvent.Load)
            assertEquals(listOf("older text"), clipboard.texts)
            assertEquals("${items[1].id} copied to the clipboard", controller.state.value.message)
            controller.messageShown()
            assertNull(controller.state.value.message)
            assertFalse(controller.state.value.busy)
        }

    @Test
    fun `loading a file saves it to the downloads`() =
        runUnconfined {
            val file = Samples.file("report.pdf", 3, secondsAfterT0 = 10)
            store.items += file
            val controller = controller()
            controller.on(PickerEvent.Select(0))
            controller.on(PickerEvent.Load)
            assertEquals("report.pdf saved to Downloads", controller.state.value.message)
            assertEquals("report.pdf", downloads.published.single().second)
        }

    @Test
    fun `cat shows the content and closing the viewer returns to the list`() =
        runUnconfined {
            store.texts[items[0].id] = "the newest"
            val controller = controller()
            controller.on(PickerEvent.Cat)
            assertEquals(Viewer(items[0], "the newest"), controller.state.value.viewer)
            controller.closeViewer()
            assertNull(controller.state.value.viewer)
            store.failRead = CoreException(CoreException.Kind.STORE, "damaged")
            controller.on(PickerEvent.Cat)
            assertEquals("cannot read ${items[0].id}: damaged", controller.state.value.picker.status)
            controller.on(PickerEvent.Load)
            assertEquals("cannot load ${items[0].id}: damaged", controller.state.value.picker.status)
            assertNull(controller.state.value.message)
        }

    @Test
    fun `details show the metadata over the list and a gone item says so`() =
        runUnconfined {
            val controller = controller()
            controller.on(PickerEvent.Details)
            val mode = controller.state.value.picker.mode as Mode.Details
            assertEquals(items[0].id, mode.title)
            assertEquals("id:      ${items[0].id}", mode.lines[0])
            assertTrue(mode.lines.any { it == "preview: newer" })
            controller.on(PickerEvent.Back)
            assertEquals(Mode.Browse, controller.state.value.picker.mode)
            store.items.clear()
            controller.on(PickerEvent.Details)
            assertEquals(Mode.Browse, controller.state.value.picker.mode)
            assertTrue(controller.state.value.picker.status!!.startsWith("cannot read ${items[0].id}"))
        }

    @Test
    fun `delete after confirming shows the list read again`() =
        runUnconfined {
            val controller = controller()
            controller.on(PickerEvent.RequestDelete)
            controller.on(PickerEvent.CancelDelete)
            assertEquals("not deleted", controller.state.value.picker.status)
            controller.on(PickerEvent.RequestDelete)
            controller.on(PickerEvent.ConfirmDelete)
            assertEquals(listOf("Loading...", "Deleting ${items[0].id}...", "Reloading..."), statuses)
            assertEquals("deleted ${items[0].id}", controller.state.value.picker.status)
            assertEquals(1, controller.state.value.picker.total)
            assertEquals(items[1].id, controller.state.value.picker.selectedItem?.id)
        }

    @Test
    fun `a failed delete and a failed reload are shown and the list stays open`() =
        runUnconfined {
            val controller = controller()
            store.failDelete = CoreException(CoreException.Kind.CONNECT, "gone away")
            controller.on(PickerEvent.RequestDelete)
            controller.on(PickerEvent.ConfirmDelete)
            assertEquals("cannot delete ${items[0].id}: gone away", controller.state.value.picker.status)
            assertEquals(2, controller.state.value.picker.total)
            store.failList = CoreException(CoreException.Kind.CONNECT, "gone away")
            controller.on(PickerEvent.ReloadServer)
            assertEquals("cannot reload: gone away", controller.state.value.picker.status)
            assertFalse(controller.state.value.busy)
        }

    @Test
    fun `a store that cannot be opened is reported and can be retried`() =
        runUnconfined {
            core.failOpen = CoreException(CoreException.Kind.CONNECT, "cannot connect to h:22")
            val controller = controller()
            assertEquals(
                Connection.Failed("cannot connect to h:22", CoreException.Kind.CONNECT),
                controller.state.value.connection,
            )
            // Events do nothing without a store.
            controller.on(PickerEvent.Reload)
            assertTrue(statuses.isEmpty())
            assertNull(controller.state.value.picker.status)
            core.failOpen = null
            controller.connect()
            assertEquals(Connection.Ready("ssh u@h:22 /srv/passalong"), controller.state.value.connection)
            assertEquals(2, controller.state.value.picker.total)
        }

    @Test
    fun `a failed first listing is shown and without a config setup is asked for`() =
        runUnconfined {
            store.failList = CoreException(CoreException.Kind.CONNECT, "dropped")
            val controller = controller()
            assertEquals("cannot load: dropped", controller.state.value.picker.status)
            assertFalse(controller.state.value.busy)
            statuses.clear()
            val unset = controller(withConfig = false)
            val failed = unset.state.value.connection as Connection.Failed
            assertEquals(CoreException.Kind.CONFIG, failed.kind)
            assertTrue(failed.message, failed.message.contains("set up"))
        }

    @Test
    fun `quitting closes the screen and events wait while busy`() =
        runUnconfined {
            val controller = controller()
            controller.on(PickerEvent.Quit)
            assertTrue(controller.state.value.quit)
        }

    @Test
    fun `events are ignored while the store is busy`() =
        runTest {
            // The standard dispatcher leaves the connect coroutine pending.
            val controller = controller()
            assertTrue(controller.state.value.busy)
            controller.on(PickerEvent.Help)
            assertEquals(Mode.Browse, controller.state.value.picker.mode)
            testScheduler.advanceUntilIdle()
            assertFalse(controller.state.value.busy)
            controller.on(PickerEvent.Help)
            assertEquals(Mode.Help, controller.state.value.picker.mode)
        }

    @Test
    fun `a failed publish is shown in the status line and the list stays open`() =
        runUnconfined {
            val file = Samples.file("report.pdf", 3, secondsAfterT0 = 10)
            store.items += file
            val controller = controller()
            downloads.fail = IOException("cannot create report.pdf in Downloads")
            controller.on(PickerEvent.Select(0))
            controller.on(PickerEvent.Load)
            assertEquals(
                "cannot load ${file.id}: cannot create report.pdf in Downloads",
                controller.state.value.picker.status,
            )
            assertNull(controller.state.value.message)
            assertFalse(controller.state.value.busy)
            assertEquals(3, controller.state.value.picker.total)
            // A failure that is not the core's in any other action lands there too.
            store.failMeta = IllegalStateException("boom")
            controller.on(PickerEvent.Details)
            assertEquals("cannot read ${file.id}: boom", controller.state.value.picker.status)
            store.failMeta = null
            store.failList = IOException("disk")
            controller.on(PickerEvent.ReloadServer)
            assertEquals("cannot reload: disk", controller.state.value.picker.status)
            assertFalse(controller.state.value.busy)
        }

    @Test
    fun `reconnecting after setup shows the new server and forgets the old list`() =
        runUnconfined {
            val controller = controller()
            controller.on(PickerEvent.StartFilter)
            controller.on(PickerEvent.FilterChanged("new"))
            assertEquals(1, controller.state.value.picker.visible.size)
            val other = FakeStore(Samples.texts("one", "two", "three"))
            other.downloadDir = files.home.resolve("Downloads")
            core.store = other
            files.writeConfig("[server]\nkind = \"https\"\n")
            controller.reconnect()
            assertEquals(Connection.Ready(other.description), controller.state.value.connection)
            assertEquals(3, controller.state.value.picker.total)
            assertEquals("the filter is cleared", "", controller.state.value.picker.filter)
            assertNull(controller.state.value.viewer)
            assertFalse(controller.state.value.busy)
            assertEquals(listOf("cached", "list(true)"), other.calls)
        }

    @Test
    fun `a config that cannot be read is reported, not thrown`() =
        runUnconfined {
            files = AppFiles(temp.newFolder())
            files.writeConfig("[server]\nkind = \"ssh\"\n")
            assertTrue("cannot run as root: the test needs an unreadable file", files.config.setReadable(false))
            val targets = ActionTargets(clipboard, downloads, Dispatchers.Unconfined, ZoneOffset.UTC)
            current = ChooseController(this, core, files, "pixel", targets)
            current.connect()
            val failed = current.state.value.connection as Connection.Failed
            assertEquals(CoreException.Kind.IO, failed.kind)
            assertTrue(failed.message, failed.message.contains("config.toml"))
            assertFalse(current.state.value.busy)
        }

    @Test
    fun `over a server, a key that expires soon is warned about and a refused key needs setup`() =
        runUnconfined {
            files = AppFiles(temp.newFolder())
            store.downloadDir = files.home.resolve("Downloads")
            files.writeConfig("[server]\nkind = \"https\"\n[server.https]\nurl = \"https://box:8443\"\n")
            val now = Instant.parse("2026-09-25T00:00:00Z")
            val inAWeek = now.plus(Duration.ofDays(7)).plusSeconds(60).epochSecond
            core.serverInfo = core.serverInfo.copy(keyExpiresAt = inAWeek)
            val targets = ActionTargets(clipboard, downloads, Dispatchers.Unconfined, ZoneOffset.UTC, now = { now })
            current = ChooseController(this, core, files, "pixel", targets)
            current.connect()
            assertEquals(Connection.Ready(store.description), current.state.value.connection)
            assertEquals(2, current.state.value.picker.total)
            assertEquals(
                "API key phone (read-write), expires 2026-10-02, in 7 days: ask the server's operator for a new key",
                current.state.value.picker.status,
            )
            assertEquals("inspectServer(saved)", core.calls.last())
            // A key with time left says nothing; a failed viewer request changes nothing.
            core.serverInfo = core.serverInfo.copy(keyExpiresAt = null)
            current.reconnect()
            assertNull(current.state.value.picker.status)
            core.failInspect = CoreException(CoreException.Kind.CONNECT, "dropped")
            current.reconnect()
            assertEquals(Connection.Ready(store.description), current.state.value.connection)
            assertNull(current.state.value.picker.status)
            // A refused device: the connection fails with the server's reason.
            val refused = "the server refused (KEY_REVOKED): ask the operator"
            core.failOpen = CoreException(CoreException.Kind.DENIED, refused)
            current.reconnect()
            assertEquals(
                Connection.Failed(refused, CoreException.Kind.DENIED),
                current.state.value.connection,
            )
        }

    @Test
    fun `over ssh no viewer request is made`() =
        runUnconfined {
            controller()
            assertFalse(core.calls.any { it.startsWith("inspectServer") })
        }
}
