package io.github.joelee.passalong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.joelee.passalong.android.AppContainer
import io.github.joelee.passalong.ui.Root
import io.github.joelee.passalong.ui.theme.PassalongTheme

/** The one activity: setup until a config exists, then the choose screen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer(applicationContext)
        setContent {
            PassalongTheme {
                Root(container, onQuit = { finish() })
            }
        }
    }
}
