package io.github.joelee.passalong.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joelee.passalong.android.AppContainer
import io.github.joelee.passalong.android.ChooseViewModel
import io.github.joelee.passalong.android.SetupViewModel
import io.github.joelee.passalong.ui.choose.ChooseScreen
import io.github.joelee.passalong.ui.setup.SetupScreen

private enum class Screen { SETUP, CHOOSE }

/**
 * Setup until the device has a config, then the choose screen; `Set up again` goes back, and a
 * setup that completes makes the choose screen connect afresh, since the config may now name
 * another server.
 */
@Composable
fun Root(
    container: AppContainer,
    onQuit: () -> Unit,
) {
    var screen by remember { mutableStateOf(if (container.files.hasConfig()) Screen.CHOOSE else Screen.SETUP) }
    var setUpAgain by remember { mutableStateOf(false) }
    when (screen) {
        Screen.SETUP -> {
            val model: SetupViewModel = viewModel(factory = container.viewModels)
            SetupScreen(
                model.controller,
                coreVersion = container.core.coreVersion(),
                onDone = {
                    setUpAgain = true
                    screen = Screen.CHOOSE
                },
            )
        }
        Screen.CHOOSE -> {
            val model: ChooseViewModel = viewModel(factory = container.viewModels)
            LaunchedEffect(setUpAgain) {
                if (setUpAgain) {
                    model.controller.reconnect()
                    setUpAgain = false
                }
            }
            ChooseScreen(
                model.controller,
                coreVersion = container.core.coreVersion(),
                onQuit = onQuit,
                onSetup = { screen = Screen.SETUP },
            )
        }
    }
}
