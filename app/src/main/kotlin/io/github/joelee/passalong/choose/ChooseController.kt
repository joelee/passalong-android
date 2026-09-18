package io.github.joelee.passalong.choose

import io.github.joelee.passalong.core.Core
import io.github.joelee.passalong.core.CoreException
import io.github.joelee.passalong.core.StoreHandle
import io.github.joelee.passalong.data.AppFiles
import io.github.joelee.passalong.data.ClipboardSink
import io.github.joelee.passalong.data.DownloadsSink
import io.github.joelee.passalong.data.ItemActions
import io.github.joelee.passalong.data.Loaded
import io.github.joelee.passalong.domain.Action
import io.github.joelee.passalong.domain.Formats
import io.github.joelee.passalong.domain.Item
import io.github.joelee.passalong.domain.Outcome
import io.github.joelee.passalong.domain.Picker
import io.github.joelee.passalong.domain.PickerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The store connection `choose` makes before it shows the list. */
sealed interface Connection {
    /** Connecting; nothing can be done yet. */
    data object Connecting : Connection

    /** Connected to the store described. */
    data class Ready(val description: String) : Connection

    /** Connecting failed; the list cannot be shown. */
    data class Failed(val message: String, val kind: CoreException.Kind) : Connection
}

/** An item's content shown as `passalong cat` prints it. */
data class Viewer(val item: Item, val text: String)

/** What the chosen action needs besides the store, as the TUI's `ActionTargets`. */
data class ActionTargets(val clipboard: ClipboardSink, val downloads: DownloadsSink)

/** Everything the choose screen shows. */
data class ChooseState(
    val picker: Picker = Picker(),
    val connection: Connection = Connection.Connecting,
    /** The store is being read or changed; touches wait, as keys do in the TUI. */
    val busy: Boolean = false,
    val viewer: Viewer? = null,
    /** A one-time message about a finished action; cleared with [ChooseController.messageShown]. */
    val message: String? = null,
    /** The user quit; the screen closes. */
    val quit: Boolean = false,
)

/**
 * `passalong choose` as the app runs it: connect, show the list from a fresh cache or the store,
 * then handle events until the user quits. Reading and changing the store happen here, and the
 * list stays open; loading and printing show their result over the list instead of closing it,
 * since there is no terminal to print into.
 */
class ChooseController(
    private val scope: CoroutineScope,
    private val core: Core,
    private val files: AppFiles,
    private val hostname: String,
    private val targets: ActionTargets,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val _state = MutableStateFlow(ChooseState())

    /** What the screen shows. */
    val state: StateFlow<ChooseState> = _state

    private var store: StoreHandle? = null
    private var actions: ItemActions? = null

    /** Connects, or connects again after a failure, and shows the list. Call it once to start. */
    fun connect() {
        _state.update { it.copy(connection = Connection.Connecting, busy = true) }
        scope.launch {
            val config = files.readConfig()
            if (config == null) {
                fail(CoreException(CoreException.Kind.CONFIG, "no config file: set up this device first"))
                return@launch
            }
            val opened =
                try {
                    core.openStore(config, files.home.path, hostname, files.listCache.path)
                } catch (err: CoreException) {
                    fail(err)
                    return@launch
                }
            store = opened
            actions = ItemActions(opened, targets.clipboard, targets.downloads)
            _state.update { it.copy(connection = Connection.Ready(opened.description)) }
            open(opened)
        }
    }

    private fun fail(err: CoreException) {
        _state.update {
            it.copy(connection = Connection.Failed(err.message.orEmpty(), err.kind), busy = false)
        }
    }

    /** The list from a usable cache, whose age the status shows, or else from the store. */
    private suspend fun open(store: StoreHandle) {
        val cached = store.cached()
        if (cached != null) {
            update { it.replace(cached.items).withStatus("cached ${cached.ageSecs} s ago") }
            _state.update { it.copy(busy = false) }
            return
        }
        update { it.withStatus("Loading...") }
        try {
            val items = store.list(true)
            update { it.replace(items).clearStatus() }
        } catch (err: CoreException) {
            update { it.withStatus("cannot load: ${err.message}") }
        }
        _state.update { it.copy(busy = false) }
    }

    /** Handles one event, as a key press in the TUI. Events wait while the store is busy. */
    fun on(event: PickerEvent) {
        if (_state.value.busy) return
        val step = _state.value.picker.handle(event)
        _state.update { it.copy(picker = step.picker) }
        val store = store ?: return
        when (val outcome = step.outcome) {
            Outcome.Continue -> Unit
            Outcome.Quit -> _state.update { it.copy(quit = true) }
            is Outcome.Act -> act(outcome)
            is Outcome.Details -> busy { details(store, outcome.id) }
            is Outcome.Delete -> busy { delete(store, outcome.id) }
            Outcome.Reload -> busy { reloadCached(store) }
            Outcome.ReloadServer -> busy { reload(store, reuse = false, prefix = "reloaded: ") }
        }
    }

    /** Closes the content view. */
    fun closeViewer() = _state.update { it.copy(viewer = null) }

    /** The one-time message was shown. */
    fun messageShown() = _state.update { it.copy(message = null) }

    private fun act(outcome: Outcome.Act) {
        val item = _state.value.picker.items.firstOrNull { it.id == outcome.id } ?: return
        val actions = actions ?: return
        busy {
            try {
                when (outcome.action) {
                    Action.LOAD -> _state.update { it.copy(message = loadedMessage(item, actions.load(item))) }
                    Action.CAT -> _state.update { it.copy(viewer = Viewer(item, actions.cat(item))) }
                }
            } catch (err: CoreException) {
                update { it.withStatus("cannot ${verb(outcome.action)} ${item.id}: ${err.message}") }
            }
        }
    }

    private suspend fun details(
        store: StoreHandle,
        id: String,
    ) {
        try {
            val meta = store.getMeta(id)
            update { it.showDetails(meta.id, Formats.metaLines(meta, zone)) }
        } catch (err: CoreException) {
            update { it.withStatus("cannot read $id: ${err.message}") }
        }
    }

    private suspend fun delete(
        store: StoreHandle,
        id: String,
    ) {
        update { it.withStatus("Deleting $id...") }
        try {
            store.delete(id)
        } catch (err: CoreException) {
            update { it.withStatus("cannot delete $id: ${err.message}") }
            return
        }
        if (reload(store, reuse = true, prefix = null) != null) {
            update { it.withStatus("deleted $id") }
        }
    }

    /** `r`: the cache when it is fresh, otherwise the store. */
    private suspend fun reloadCached(store: StoreHandle) {
        val cached = store.cached()
        if (cached != null) {
            update {
                it.replace(
                    cached.items,
                ).withStatus("reloaded: ${cached.items.size} items, cached ${cached.ageSecs} s ago")
            }
        } else {
            reload(store, reuse = true, prefix = "reloaded: ")
        }
    }

    /**
     * Reads the store again, showing `Reloading...` meanwhile, and returns how many items there
     * are, or null when reading failed, which the status then reports. With [prefix], the count is
     * shown as `<prefix><n> items`.
     */
    private suspend fun reload(
        store: StoreHandle,
        reuse: Boolean,
        prefix: String?,
    ): Int? {
        update { it.withStatus("Reloading...") }
        return try {
            val items = store.list(reuse)
            update { picker ->
                val replaced = picker.replace(items)
                if (prefix != null) replaced.withStatus("$prefix${items.size} items") else replaced
            }
            items.size
        } catch (err: CoreException) {
            update { it.withStatus("cannot reload: ${err.message}") }
            null
        }
    }

    private fun busy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        scope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private inline fun update(change: (Picker) -> Picker) = _state.update { it.copy(picker = change(it.picker)) }
}

private fun verb(action: Action): String =
    when (action) {
        Action.LOAD -> "load"
        Action.CAT -> "read"
    }

private fun loadedMessage(
    item: Item,
    loaded: Loaded,
): String =
    when (loaded) {
        Loaded.Clipboard -> "${item.id} copied to the clipboard"
        is Loaded.Downloaded -> "${loaded.name} saved to Downloads"
    }
