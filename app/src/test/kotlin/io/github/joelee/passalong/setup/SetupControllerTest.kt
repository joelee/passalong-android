package io.github.joelee.passalong.setup

import io.github.joelee.passalong.core.CoreException
import io.github.joelee.passalong.core.HostKeyInfo
import io.github.joelee.passalong.data.AppFiles
import io.github.joelee.passalong.support.FakeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class SetupControllerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val core = FakeCore()
    private lateinit var files: AppFiles

    private fun TestScope.controller(): SetupController {
        files = AppFiles(temp.newFolder("home"))
        return SetupController(this, core, files, "pixel-9", hostKeyTimeoutSecs = 3, io = Dispatchers.Unconfined)
            .also { it.start() }
    }

    private fun runUnconfined(block: suspend TestScope.() -> Unit) =
        runTest(
            UnconfinedTestDispatcher(),
            testBody = block,
        )

    private fun SetupController.fillIn() {
        hostChanged(" nas.local ")
        portChanged("2222")
        userChanged("pa")
        remotePathChanged("/srv/pa")
        deviceNameChanged("phone")
    }

    @Test
    fun `a new device gets a key at once and the fields default as init does`() =
        runUnconfined {
            val controller = controller()
            val state = controller.state.value
            assertEquals("22", state.port)
            assertEquals("passalong", state.user)
            assertEquals("/srv/passalong", state.remotePath)
            assertEquals("pixel-9", state.deviceName)
            assertEquals("ssh-ed25519 AAAAgenerated1 pixel-9", state.publicKey)
            assertEquals("ssh-ed25519 AAAAgenerated1 pixel-9", files.readPublicKey())
            assertFalse("the host is empty", state.canFetch)
            // A second controller finds the key and keeps it.
            val again = SetupController(this, core, files, "pixel-9", io = Dispatchers.Unconfined).also { it.start() }
            assertEquals(1, core.generated)
            assertEquals(state.publicKey, again.state.value.publicKey)
            again.generateKey(replace = true)
            assertEquals("ssh-ed25519 AAAAgenerated2 pixel-9", again.state.value.publicKey)
        }

    @Test
    fun `confirming the fingerprint writes a config that validates`() =
        runUnconfined {
            val controller = controller()
            controller.fillIn()
            assertTrue(controller.state.value.canFetch)
            controller.fetchHostKey()
            assertEquals("fetchHostKey(nas.local, 2222, 3)", core.calls.last())
            val state = controller.state.value
            assertEquals(core.hostKey, state.hostKey)
            assertFalse(state.busy)
            assertFalse("nothing written before confirming", files.hasConfig())
            controller.fingerprintMatches(true)
            assertTrue(controller.state.value.done)
            assertNull(controller.state.value.hostKey)
            val written = files.readConfig()!!
            assertTrue(written, written.contains("host = \"nas.local\""))
            assertTrue(written, written.contains("port = 2222"))
            assertTrue(written, written.contains("user = \"pa\""))
            assertTrue(written, written.contains("host_key = \"ssh-ed25519 AAAAtest\""))
            assertTrue(written, written.contains("identity_file = \"${files.identity.path}\""))
            assertTrue(written, written.contains("remote_path = \"/srv/pa\""))
            assertTrue(written, written.contains("device_name = \"phone\""))
            assertEquals("validateConfig", core.calls.last())
        }

    @Test
    fun `declining the fingerprint writes nothing`() =
        runUnconfined {
            val controller = controller()
            controller.fillIn()
            controller.fetchHostKey()
            controller.fingerprintMatches(false)
            assertFalse(files.hasConfig())
            assertNull(controller.state.value.hostKey)
            assertEquals("nothing was written", controller.state.value.error)
            assertFalse(controller.state.value.done)
            // Without a fetched key there is nothing to confirm.
            controller.fingerprintMatches(true)
            assertFalse(files.hasConfig())
        }

    @Test
    fun `a bad port, a missing key, or a failed fetch are refused or reported`() =
        runUnconfined {
            val controller = controller()
            controller.fillIn()
            controller.portChanged("70000")
            assertNull(controller.state.value.portNumber)
            assertFalse(controller.state.value.canFetch)
            controller.fetchHostKey()
            assertNull(controller.state.value.hostKey)
            controller.portChanged("22")
            core.failFetch = CoreException(CoreException.Kind.CONNECT, "connecting to nas.local:22 timed out after 3 s")
            controller.fetchHostKey()
            assertEquals("connecting to nas.local:22 timed out after 3 s", controller.state.value.error)
            assertFalse(controller.state.value.busy)
            core.failFetch = null
            controller.fetchHostKey()
            assertNull(controller.state.value.error)
            // Changing the host or port forgets a fetched key.
            controller.hostChanged("other")
            assertNull(controller.state.value.hostKey)
            controller.fetchHostKey()
            controller.portChanged("23")
            assertNull(controller.state.value.hostKey)
        }

    @Test
    fun `key and config problems are shown`() =
        runUnconfined {
            core.failGenerate = CoreException(CoreException.Kind.IO, "cannot write the key")
            val controller = controller()
            assertNull(controller.state.value.publicKey)
            assertEquals("cannot write the key", controller.state.value.error)
            assertFalse(controller.state.value.canFetch)
            core.failGenerate = null
            controller.generateKey(replace = false)
            assertNull(controller.state.value.error)
            controller.fillIn()
            controller.fetchHostKey()
            core.failValidate = CoreException(CoreException.Kind.CONFIG, "server.ssh.host: invalid")
            controller.fingerprintMatches(true)
            assertEquals("server.ssh.host: invalid", controller.state.value.error)
            assertFalse(files.hasConfig())
            assertFalse(controller.state.value.done)
        }

    @Test
    fun `fetching waits for the server`() =
        runTest {
            val controller = controller()
            assertTrue("the key is made off the main thread", controller.state.value.busy)
            testScheduler.advanceUntilIdle()
            assertFalse(controller.state.value.busy)
            controller.fillIn()
            core.hostKey = HostKeyInfo("ssh-ed25519 AAAAother", "SHA256:other", "ssh-ed25519")
            controller.fetchHostKey()
            assertTrue(controller.state.value.busy)
            assertFalse(controller.state.value.canFetch)
            testScheduler.advanceUntilIdle()
            assertEquals("SHA256:other", controller.state.value.hostKey?.fingerprint)
        }

    @Test
    fun `a config that cannot be written is reported and nothing is done`() =
        runUnconfined {
            val controller = controller()
            controller.fillIn()
            controller.fetchHostKey()
            // A folder where the config file goes: the write fails.
            files.config.mkdirs()
            controller.fingerprintMatches(true)
            val error = controller.state.value.error
            assertTrue("$error", error != null && error.contains("config.toml"))
            assertFalse(controller.state.value.done)
            assertNull(controller.state.value.hostKey)
        }
}
