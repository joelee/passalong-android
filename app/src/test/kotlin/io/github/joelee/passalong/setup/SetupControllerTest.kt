package io.github.joelee.passalong.setup

import io.github.joelee.passalong.core.CoreException
import io.github.joelee.passalong.core.HostKeyInfo
import io.github.joelee.passalong.core.PresentedCertificate
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
    private val unconfined = SetupOptions(hostKeyTimeoutSecs = 3, io = Dispatchers.Unconfined)

    private fun TestScope.controller(): SetupController {
        files = AppFiles(temp.newFolder("home"))
        return SetupController(this, core, files, "pixel-9", unconfined).also { it.start() }
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
            val again = SetupController(this, core, files, "pixel-9", unconfined).also { it.start() }
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

    private fun SetupController.fillInServer(url: String = " https://box.example:8443 ") {
        kindChanged(ServerKind.SERVER)
        urlChanged(url)
        deviceNameChanged("phone")
    }

    @Test
    fun `the kind defaults to ssh and a server needs a url before connecting`() =
        runUnconfined {
            val controller = controller()
            assertEquals(ServerKind.SSH, controller.state.value.kind)
            controller.kindChanged(ServerKind.SERVER)
            assertFalse(controller.state.value.canConnect)
            controller.urlChanged("https://box.example:8443")
            assertTrue(controller.state.value.canConnect)
            assertFalse("the SSH host key is not fetched for a server", controller.state.value.canFetch)
            // A plain http URL is refused by the config before any connection.
            controller.urlChanged("http://box.example:8443")
            controller.connect()
            assertTrue(controller.state.value.error!!, controller.state.value.error!!.contains("http://"))
            assertFalse(core.calls.any { it.startsWith("probeServer") })
        }

    @Test
    fun `an untrusted certificate needs the operator's pin and three wrong ones stop the flow`() =
        runUnconfined {
            val pin = "sha256/Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU="
            core.presented = PresentedCertificate(pin, trusted = false)
            val controller = controller()
            controller.fillInServer()
            controller.connect()
            assertEquals("probeServer(https://box.example:8443)", core.calls.last())
            var step = controller.state.value.serverStep as ServerStep.ConfirmPin
            assertEquals(pin, step.presented.pin)
            assertEquals(3, step.attemptsLeft)
            controller.trustSystem()
            assertTrue(
                "not trusted: the system cannot vouch",
                controller.state.value.serverStep is ServerStep.ConfirmPin,
            )
            controller.pinTyped("nonsense")
            step = controller.state.value.serverStep as ServerStep.ConfirmPin
            assertEquals(2, step.attemptsLeft)
            assertTrue(step.problem!!, step.problem!!.contains("sha256/"))
            controller.pinTyped("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            step = controller.state.value.serverStep as ServerStep.ConfirmPin
            assertEquals(1, step.attemptsLeft)
            assertEquals("That is not the pin the server presented.", step.problem)
            controller.pinTyped("nonsense")
            assertNull(controller.state.value.serverStep)
            assertEquals("the server's pin was not confirmed; nothing was written", controller.state.value.error)
            assertFalse(files.hasConfig())
            assertFalse(files.apiKey.exists())
        }

    @Test
    fun `a pin in curl's form is accepted and a typed key is saved before the config`() =
        runUnconfined {
            val pin = "sha256/Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU="
            core.presented = PresentedCertificate(pin, trusted = false)
            val controller = controller()
            controller.fillInServer()
            controller.connect()
            controller.pinTyped(" " + pin.replaceFirst("/", "//") + "\n")
            var step = controller.state.value.serverStep
            assertEquals(ServerStep.TypeApiKey(3, problem = null), step)
            controller.apiKeyTyped("not a key")
            step = controller.state.value.serverStep as ServerStep.TypeApiKey
            assertEquals(2, step.attemptsLeft)
            assertTrue(step.problem!!, step.problem!!.contains("pal_"))
            assertFalse(core.calls.any { it.startsWith("inspectServer") })
            controller.apiKeyTyped(" pal_0123abcd_s3cr3t ")
            assertEquals("inspectServer(typed)", core.calls.last())
            val claim = controller.state.value.serverStep as ServerStep.ConfirmPlaintext
            assertEquals("home", claim.info.workspace)
            assertEquals(
                "connected to passalong-server 0.1.0: API key phone (read-write), never expires",
                claim.summary,
            )
            assertFalse("nothing written before the claim is confirmed", files.hasConfig())
            assertFalse(files.apiKey.exists())
            controller.plaintextConfirmed(true)
            assertTrue(controller.state.value.done)
            assertNull(controller.state.value.serverStep)
            assertEquals(listOf("pal_0123abcd_s3cr3t"), core.savedKeys)
            assertEquals("pal_0123abcd_s3cr3t\n", files.apiKey.readText())
            val written = files.readConfig()!!
            assertTrue(written, written.contains("url = \"https://box.example:8443\""))
            assertTrue(written, written.contains("tls_pin = \"$pin\""))
            assertTrue(written, written.contains("api_key_file = \"${files.apiKey.path}\""))
            assertTrue(written, written.contains("device_name = \"phone\""))
        }

    @Test
    fun `a trusted certificate may go without a pin and a saved key is used unasked`() =
        runUnconfined {
            core.presented = PresentedCertificate("sha256/Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU=", trusted = true)
            core.serverInfo = core.serverInfo.copy(encryptionState = "sealed", encryptionKeyId = "3f9a2c1d3f9a2c1d")
            val controller = controller()
            files.apiKey.writeText("pal_0123abcd_saved\n")
            controller.fillInServer()
            controller.connect()
            controller.trustSystem()
            assertEquals("inspectServer(saved)", core.calls.last())
            // A sealed workspace needs no claim confirmed: the config is written at once.
            assertTrue(controller.state.value.done)
            assertTrue(core.savedKeys.isEmpty())
            val written = files.readConfig()!!
            assertTrue(written, written.contains("# No tls_pin"))
            assertFalse(written, written.contains("tls_pin ="))
        }

    @Test
    fun `declining the plaintext claim or a refused key writes nothing`() =
        runUnconfined {
            core.presented = PresentedCertificate("sha256/Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU=", trusted = true)
            val controller = controller()
            controller.fillInServer()
            controller.connect()
            controller.trustSystem()
            controller.apiKeyTyped("pal_0123abcd_s3cr3t")
            assertTrue(controller.state.value.serverStep is ServerStep.ConfirmPlaintext)
            controller.plaintextConfirmed(false)
            assertNull(controller.state.value.serverStep)
            assertTrue(
                controller.state.value.error!!.startsWith("the server's claim was not confirmed; nothing was written"),
            )
            assertFalse(files.hasConfig())
            assertFalse(files.apiKey.exists())
            // A device with a store key never asks: it would refuse plaintext itself.
            core.hasStoreKey = true
            controller.connect()
            controller.trustSystem()
            controller.apiKeyTyped("pal_0123abcd_s3cr3t")
            assertTrue(controller.state.value.done)
            // A refused key is reported, and nothing written.
            files.config.delete()
            files.apiKey.delete()
            core.savedKeys.clear()
            core.failInspect = CoreException(CoreException.Kind.DENIED, "the server refused (KEY_REVOKED)")
            val again = SetupController(this, core, files, "pixel-9", unconfined).also { it.start() }
            again.fillInServer()
            again.connect()
            again.trustSystem()
            again.apiKeyTyped("pal_0123abcd_s3cr3t")
            assertEquals("the server refused (KEY_REVOKED)", again.state.value.error)
            assertFalse(again.state.value.done)
            assertFalse(files.hasConfig())
            assertTrue(core.savedKeys.isEmpty())
        }

    @Test
    fun `cancelling the pin or the key, and a failed probe, are reported`() =
        runUnconfined {
            val controller = controller()
            controller.fillInServer()
            controller.connect()
            controller.cancelPin()
            assertEquals("the server's pin was not confirmed; nothing was written", controller.state.value.error)
            core.presented = PresentedCertificate("sha256/Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU=", trusted = true)
            controller.connect()
            controller.trustSystem()
            controller.cancelApiKey()
            assertEquals("no API key was typed; nothing was written", controller.state.value.error)
            controller.connect()
            controller.trustSystem()
            repeat(3) { controller.apiKeyTyped("bad") }
            assertEquals("no usable API key was typed; nothing was written", controller.state.value.error)
            core.failProbe = CoreException(CoreException.Kind.CONNECT, "cannot reach https://box.example:8443: refused")
            controller.connect()
            assertEquals("cannot reach https://box.example:8443: refused", controller.state.value.error)
            assertNull(controller.state.value.serverStep)
            // Changing the URL forgets a probe.
            core.failProbe = null
            controller.connect()
            assertTrue(controller.state.value.serverStep is ServerStep.ConfirmPin)
            controller.urlChanged("https://other:8443")
            assertNull(controller.state.value.serverStep)
        }
}
