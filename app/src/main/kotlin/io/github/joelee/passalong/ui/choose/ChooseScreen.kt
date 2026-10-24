package io.github.joelee.passalong.ui.choose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joelee.passalong.choose.ChooseController
import io.github.joelee.passalong.choose.ChooseState
import io.github.joelee.passalong.choose.Connection
import io.github.joelee.passalong.choose.Viewer
import io.github.joelee.passalong.core.CoreException
import io.github.joelee.passalong.domain.Formats
import io.github.joelee.passalong.domain.Item
import io.github.joelee.passalong.domain.Mode
import io.github.joelee.passalong.domain.PickerEvent
import java.time.ZoneId

/** `passalong choose`: the list, its filter, and the dialogs over it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseScreen(
    controller: ChooseController,
    coreVersion: String,
    onQuit: () -> Unit,
    onSetup: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val zone = remember { ZoneId.systemDefault() }
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler { controller.on(PickerEvent.Back) }
    LaunchedEffect(state.quit) { if (state.quit) onQuit() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        controller.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passalong") },
                actions = {
                    IconButton(onClick = { controller.on(PickerEvent.StartFilter) }) {
                        Icon(Icons.Default.Search, contentDescription = "Filter (/)")
                    }
                    IconButton(onClick = { controller.on(PickerEvent.Reload) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload (r)")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Read from the server (R)") },
                            onClick = {
                                menuOpen = false
                                controller.on(PickerEvent.ReloadServer)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Help (?)") },
                            onClick = {
                                menuOpen = false
                                controller.on(PickerEvent.Help)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Set up again") },
                            onClick = {
                                menuOpen = false
                                onSetup()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Quit (q)") },
                            onClick = {
                                menuOpen = false
                                controller.on(PickerEvent.Quit)
                            },
                        )
                    }
                },
            )
        },
        bottomBar = { StatusBar(state) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.picker.mode == Mode.Filter || state.picker.filter.isNotEmpty()) {
                FilterField(state, controller)
            }
            when (val connection = state.connection) {
                Connection.Connecting -> Centered { CircularProgressIndicator() }
                is Connection.Failed -> ConnectionFailed(connection, onRetry = controller::connect, onSetup = onSetup)
                is Connection.Ready -> ItemList(state, controller, zone)
            }
        }
    }

    when (val mode = state.picker.mode) {
        is Mode.ConfirmDelete -> ConfirmDeleteDialog(mode.id, controller)
        is Mode.Details -> DetailsDialog(mode, onClose = { controller.on(PickerEvent.Back) })
        Mode.Help -> HelpDialog(coreVersion, onClose = { controller.on(PickerEvent.Back) })
        Mode.Browse, Mode.Filter -> Unit
    }
    state.viewer?.let { ViewerDialog(it, onClose = controller::closeViewer) }
}

@Composable
private fun FilterField(
    state: ChooseState,
    controller: ChooseController,
) {
    OutlinedTextField(
        value = state.picker.filter,
        onValueChange = { controller.on(PickerEvent.FilterChanged(it)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
        label = { Text("Filter: id, name, device, or kind") },
        trailingIcon = {
            IconButton(onClick = { controller.on(PickerEvent.ClearFilter) }) {
                Icon(Icons.Default.Close, contentDescription = "Clear the filter (Esc)")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { controller.on(PickerEvent.KeepFilter) }),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemList(
    state: ChooseState,
    controller: ChooseController,
    zone: ZoneId,
) {
    val visible = state.picker.visible
    if (visible.isEmpty()) {
        Centered { Text(if (state.picker.total == 0) "no items" else "nothing matches the filter") }
        return
    }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
            Box {
                ItemRow(
                    item = item,
                    selected = index == state.picker.selected,
                    zone = zone,
                    modifier =
                        Modifier.combinedClickable(
                            onClick = {
                                controller.on(PickerEvent.Select(index))
                                controller.on(PickerEvent.Load)
                            },
                            onLongClick = {
                                controller.on(PickerEvent.Select(index))
                                menuFor = index
                            },
                        ),
                )
                RowMenu(
                    expanded = menuFor == index,
                    onDismiss = { menuFor = null },
                    onEvent = { event ->
                        menuFor = null
                        controller.on(event)
                    },
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ItemRow(
    item: Item,
    selected: Boolean,
    zone: ZoneId,
    modifier: Modifier,
) {
    val colors =
        if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        }
    ListItem(
        headlineContent = { Text(item.displayName, maxLines = 2) },
        supportingContent = { Text(Formats.rowDetails(item, zone)) },
        overlineContent = { Text(item.contentKey, fontFamily = FontFamily.Monospace) },
        colors = colors,
        modifier = modifier,
    )
}

@Composable
private fun RowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEvent: (PickerEvent) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Load (Enter)") }, onClick = { onEvent(PickerEvent.Load) })
        DropdownMenuItem(text = { Text("Show content (c)") }, onClick = { onEvent(PickerEvent.Cat) })
        DropdownMenuItem(text = { Text("Details (g)") }, onClick = { onEvent(PickerEvent.Details) })
        DropdownMenuItem(text = { Text("Delete (d)") }, onClick = { onEvent(PickerEvent.RequestDelete) })
    }
}

@Composable
private fun StatusBar(state: ChooseState) {
    val store = (state.connection as? Connection.Ready)?.description
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.picker.status ?: store ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                val count = state.picker.visible.size
                val total = state.picker.total
                val shown = if (count == total) "$total items" else "$count of $total items"
                Text(shown, style = MaterialTheme.typography.labelSmall)
            }
            if (state.busy) CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ConnectionFailed(
    failed: Connection.Failed,
    onRetry: () -> Unit,
    onSetup: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Cannot open the store", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(failed.message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        // A device the server refused for good needs a new key, which only setup can take.
        if (failed.kind == CoreException.Kind.DENIED) {
            Row {
                Button(onClick = onSetup) { Text("Set up again") }
                Spacer(Modifier.padding(8.dp))
                OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
        } else {
            Row {
                Button(onClick = onRetry) { Text("Try again") }
                Spacer(Modifier.padding(8.dp))
                OutlinedButton(onClick = onSetup) { Text("Set up again") }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ConfirmDeleteDialog(
    id: String,
    controller: ChooseController,
) {
    AlertDialog(
        onDismissRequest = { controller.on(PickerEvent.CancelDelete) },
        title = { Text("Delete this item?") },
        text = { Text(id, fontFamily = FontFamily.Monospace) },
        confirmButton = { TextButton(onClick = { controller.on(PickerEvent.ConfirmDelete) }) { Text("Delete (y)") } },
        dismissButton = { TextButton(onClick = { controller.on(PickerEvent.CancelDelete) }) { Text("Keep") } },
    )
}

@Composable
private fun DetailsDialog(
    details: Mode.Details,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(details.title, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                details.lines.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun HelpDialog(
    coreVersion: String,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("passalong $coreVersion") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Lightweight clipboard and file sharing over SSH. This list is passalong choose: " +
                        "the stored items, newest first.",
                )
                Spacer(Modifier.height(12.dp))
                HELP_LINES.forEach { (gesture, meaning) ->
                    Text("$gesture — $meaning", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private val HELP_LINES =
    listOf(
        "Tap" to "load the item: text to the clipboard, files to Downloads (Enter)",
        "Long press" to "show its content (c), its details (g), or delete it (d)",
        "Search" to "filter by id, name or preview, device, or kind (/)",
        "Refresh" to "reload the list, from the cache when it is fresh (r)",
        "Menu" to "read the list from the server (R), help (?), set up again, quit (q)",
        "Back" to "close what is open, or quit (Esc)",
    )

@Composable
private fun ViewerDialog(
    viewer: Viewer,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(viewer.item.id, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                HorizontalDivider()
                Text(
                    viewer.text,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                        Modifier.weight(
                            1f,
                        ).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp),
                )
            }
        }
    }
}
