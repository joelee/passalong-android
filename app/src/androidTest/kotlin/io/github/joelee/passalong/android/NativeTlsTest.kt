package io.github.joelee.passalong.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.joelee.passalong.core.CoreException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TLS through the device: the bridge's verifier is initialised with the app's context and can
 * say whether the system trusts a server's certificate. The self-signed test server is optional:
 * pass its URL and pin as `-Pandroid.testInstrumentationRunnerArguments.server=<url>` and
 * `…pin=<sha256/...>` (see docs/developer-guide.md); without them that case is skipped.
 */
@RunWith(AndroidJUnit4::class)
class NativeTlsTest {
    private val core = FfiCore()

    @Before
    fun initialise() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("the verifier initialises with the app's context", NativeTls.init(context))
    }

    @Test
    fun a_publicly_trusted_certificate_is_trusted_by_the_system() =
        runBlocking {
            val presented = core.probeServer("https://dl.google.com")
            assertTrue(presented.pin.startsWith("sha256/"))
            assertTrue("the system trusts it", presented.trusted)
        }

    @Test
    fun a_self_signed_test_server_is_pinned_and_not_trusted() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val server = arguments.getString("server")
            val pin = arguments.getString("pin")
            assumeTrue("no test server given", server != null && pin != null)
            val presented = core.probeServer(server!!)
            assertEquals(pin, presented.pin)
            assertFalse("self-signed: the system does not trust it", presented.trusted)
        }

    @Test
    fun a_server_that_is_not_there_is_a_connect_failure() =
        runBlocking {
            val err = runCatching { core.probeServer("https://10.0.2.2:1") }.exceptionOrNull()
            assertTrue("$err", err is CoreException && err.kind == CoreException.Kind.CONNECT)
        }
}
