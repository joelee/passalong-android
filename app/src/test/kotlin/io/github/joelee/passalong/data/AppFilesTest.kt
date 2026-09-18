package io.github.joelee.passalong.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `paths live under the home and the config is written atomically`() {
        val home = File(temp.root, "files")
        val files = AppFiles(home)
        assertEquals(File(home, "config.toml"), files.config)
        assertEquals(File(home, "keys/id_ed25519"), files.identity)
        assertEquals(File(home, "keys/id_ed25519.pub"), files.publicKey)
        assertEquals(File(home, "list-cache.json"), files.listCache)
        assertFalse(files.hasConfig())
        assertNull(files.readConfig())
        assertNull(files.readPublicKey())
        files.writeConfig("[server]\nkind = \"ssh\"\n")
        assertTrue(files.hasConfig())
        assertEquals("[server]\nkind = \"ssh\"\n", files.readConfig())
        assertFalse(File(home, "config.toml.tmp").exists())
        files.writeConfig("second")
        assertEquals("second", files.readConfig())
        files.publicKey.parentFile!!.mkdirs()
        files.publicKey.writeText("ssh-ed25519 AAAA pixel\n")
        assertEquals("ssh-ed25519 AAAA pixel", files.readPublicKey())
        files.publicKey.writeText("\n")
        assertNull(files.readPublicKey())
    }
}
