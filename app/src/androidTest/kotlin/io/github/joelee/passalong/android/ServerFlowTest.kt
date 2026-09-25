package io.github.joelee.passalong.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.joelee.passalong.choose.ActionTargets
import io.github.joelee.passalong.choose.ChooseController
import io.github.joelee.passalong.choose.ChooseState
import io.github.joelee.passalong.choose.Connection
import io.github.joelee.passalong.data.AppFiles
import io.github.joelee.passalong.domain.PickerEvent
import io.github.joelee.passalong.setup.ServerKind
import io.github.joelee.passalong.setup.ServerStep
import io.github.joelee.passalong.setup.SetupController
import io.github.joelee.passalong.setup.SetupState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole flow on the device against a real passalong-server: the setup screen's controller
 * over the real bridge, then the choose screen's controller listing, reading, loading, and
 * deleting what the CLI put there. Needs a server and its details as instrumentation
 * arguments (`server`, `pin`, `key`, and `text`, the text of an item the server holds); skipped
 * without them. See docs/developer-guide.md.
 */
@RunWith(AndroidJUnit4::class)
class ServerFlowTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var files: AppFiles

    @Before
    fun freshFiles() {
        assumeTrue("no test server given", arguments.getString("server") != null)
        assertTrue(NativeTls.init(context))
        files = AppFiles(File(context.filesDir, "server-flow-test"))
        files.home.deleteRecursively()
    }

    @Test
    fun setup_then_choose_over_a_passalong_server() =
        runBlocking {
            val server = arguments.getString("server")!!
            val pin = arguments.getString("pin")!!
            val key = arguments.getString("key")!!
            val text = arguments.getString("text")!!

            val setup = SetupController(MainScope(), FfiCore(), files, "emulator")
            setup.kindChanged(ServerKind.SERVER)
            setup.urlChanged(server)
            setup.deviceNameChanged("emulator")
            setup.connect()
            val presented =
                await<ServerStep.ConfirmPin>(setup.state.value) { it.serverStep as? ServerStep.ConfirmPin }
                    ?: awaitSetup(setup) { it.serverStep as? ServerStep.ConfirmPin }
            assertEquals(pin, presented.presented.pin)
            setup.pinTyped(pin)
            awaitSetup(setup) { it.serverStep as? ServerStep.TypeApiKey }
            setup.apiKeyTyped(key)
            val claim = awaitSetup(setup) { it.serverStep as? ServerStep.ConfirmPlaintext }
            assertTrue(claim.summary, claim.summary.startsWith("connected to passalong-server"))
            setup.plaintextConfirmed(true)
            awaitSetup(setup) { if (it.done) Unit else null }
            assertTrue(files.hasConfig())
            assertTrue(files.apiKey.isFile)

            val copied = mutableListOf<String>()
            val published = mutableListOf<String>()
            val targets =
                ActionTargets(
                    clipboard = { copied += it },
                    downloads = { _, name, _ ->
                        published += name
                        name
                    },
                )
            val choose = ChooseController(MainScope(), FfiCore(), files, "emulator", targets)
            choose.connect()
            awaitChoose(choose) { (it.connection as? Connection.Ready)?.takeIf { _ -> !it.busy } }
            assertEquals(Connection.Ready("https ${server.trimEnd('/')}"), choose.state.value.connection)
            val items = choose.state.value.picker.items
            assertTrue("the CLI put items there: $items", items.size >= 2)
            val textItem = items.first { it.isText }
            val fileItem = items.first { !it.isText }

            choose.on(PickerEvent.Select(items.indexOf(textItem)))
            choose.on(PickerEvent.Cat)
            val viewer = awaitChoose(choose) { it.viewer }
            assertEquals(text, viewer.text)
            choose.closeViewer()

            choose.on(PickerEvent.Select(items.indexOf(fileItem)))
            choose.on(PickerEvent.Load)
            awaitChoose(choose) { it.message }
            assertEquals(listOf(fileItem.name), published)

            choose.on(PickerEvent.Select(items.indexOf(textItem)))
            choose.on(PickerEvent.Load)
            awaitChoose(choose) { it.message?.takeIf { m -> m.contains("clipboard") } }
            assertEquals(listOf(text), copied)

            choose.on(PickerEvent.Select(items.indexOf(fileItem)))
            choose.on(PickerEvent.RequestDelete)
            choose.on(PickerEvent.ConfirmDelete)
            awaitChoose(choose) { it.picker.status?.takeIf { s -> s.startsWith("deleted ") } }
            assertEquals(items.size - 1, choose.state.value.picker.total)
            assertNull(choose.state.value.picker.items.firstOrNull { it.id == fileItem.id })
        }

    private suspend fun <T> awaitSetup(
        controller: SetupController,
        pick: (SetupState) -> T?,
    ): T =
        withTimeout(TIMEOUT_MS) {
            while (true) {
                val state = controller.state.value
                pick(state)?.let { return@withTimeout it }
                state.error?.let { error("setup failed: $it") }
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private suspend fun <T> awaitChoose(
        controller: ChooseController,
        pick: (ChooseState) -> T?,
    ): T =
        withTimeout(TIMEOUT_MS) {
            while (true) {
                val state = controller.state.value
                pick(state)?.let { return@withTimeout it }
                (state.connection as? Connection.Failed)?.let { error("choose failed: ${it.message}") }
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private fun <T> await(
        state: SetupState,
        pick: (SetupState) -> T?,
    ): T? = pick(state)

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 100L
    }
}
