package io.github.joelee.passalong.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppContainerTest {
    @Test
    fun `device names keep only safe characters`() {
        assertEquals("Pixel-9-Pro", AppContainer.deviceName("Pixel 9 Pro"))
        assertEquals("SM-S928B", AppContainer.deviceName("SM-S928B"))
        assertEquals("android", AppContainer.deviceName("  "))
        assertEquals("a.b_c", AppContainer.deviceName("a.b_c!!"))
    }
}
