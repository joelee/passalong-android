package io.github.joelee.passalong.domain

/** What to do with the chosen item. */
enum class Action {
    /** As `passalong load <ID>`: text to the clipboard, files to Downloads. */
    LOAD,

    /** As `passalong cat <ID>`: show the content. */
    CAT,
}

/** What keys, or here touches, mean at the moment; the TUI's `Mode`. */
sealed interface Mode {
    /** Touches move and act. */
    data object Browse : Mode

    /** The filter is being typed. */
    data object Filter : Mode

    /** Deleting [id] waits for confirmation. */
    data class ConfirmDelete(val id: String) : Mode

    /** The help is shown; closing it changes nothing. */
    data object Help : Mode

    /** [lines] about one item are shown under [title]. */
    data class Details(val title: String, val lines: List<String>) : Mode
}

/** What the screen asks the picker to do; each one is a key of the TUI. */
sealed interface PickerEvent {
    /** A row was touched; `Up`/`Down` in the TUI. */
    data class Select(val index: Int) : PickerEvent

    /** `/`: start filtering. */
    data object StartFilter : PickerEvent

    /** Typing in the filter. */
    data class FilterChanged(val text: String) : PickerEvent

    /** Enter while filtering: keep the filter and stop typing. */
    data object KeepFilter : PickerEvent

    /** Esc while filtering: clear the filter. */
    data object ClearFilter : PickerEvent

    /** Enter: load the selected item. */
    data object Load : PickerEvent

    /** `c`: show the selected item's content. */
    data object Cat : PickerEvent

    /** `g`: show the selected item's metadata. */
    data object Details : PickerEvent

    /** `d`: ask before deleting the selected item. */
    data object RequestDelete : PickerEvent

    /** `y` after `d`: delete it. */
    data object ConfirmDelete : PickerEvent

    /** Any other key after `d`: keep it. */
    data object CancelDelete : PickerEvent

    /** `r`: show the list again, from the cache when it is fresh. */
    data object Reload : PickerEvent

    /** `R`: read the list from the server. */
    data object ReloadServer : PickerEvent

    /** `?`: show the help. */
    data object Help : PickerEvent

    /** Esc, or the back gesture: close what is open, or quit from the list. */
    data object Back : PickerEvent

    /** `q`: quit. */
    data object Quit : PickerEvent
}

/** What an event led to; the TUI's `Outcome`. */
sealed interface Outcome {
    /** Keep going. */
    data object Continue : Outcome

    /** Close the list and do nothing. */
    data object Quit : Outcome

    /** Act on the item. */
    data class Act(val action: Action, val id: String) : Outcome

    /** Delete the item and stay in the list. */
    data class Delete(val id: String) : Outcome

    /** Show the item's metadata over the list. */
    data class Details(val id: String) : Outcome

    /** Show the list again: from the list cache when it is fresh. */
    data object Reload : Outcome

    /** Read the list from the server. */
    data object ReloadServer : Outcome
}

/** A picker after an event, and what the event led to. */
data class Step(val picker: Picker, val outcome: Outcome)

/**
 * The items, the filter, the selection, and a status message: the TUI's picker state, which knows
 * nothing about the screen. Every change returns a new picker.
 */
data class Picker(
    val items: List<Item> = emptyList(),
    val filter: String = "",
    val mode: Mode = Mode.Browse,
    val selected: Int = 0,
    val status: String? = null,
) {
    /** The items the filter lets through, in order. */
    val visible: List<Item>
        get() {
            val needle = filter.lowercase()
            return if (needle.isEmpty()) items else items.filter { it.matches(needle) }
        }

    /** The selected item, if any is visible. */
    val selectedItem: Item?
        get() = visible.getOrNull(selected)

    /** How many items there are, filtered or not. */
    val total: Int
        get() = items.size

    /** Shows [text] in the status line. */
    fun withStatus(text: String): Picker = copy(status = text)

    /** Clears the status line. */
    fun clearStatus(): Picker = copy(status = null)

    /** Shows [lines] in a dialog titled [title]. */
    fun showDetails(
        title: String,
        lines: List<String>,
    ): Picker = copy(mode = Mode.Details(title, lines))

    /** Replaces the items, keeping the selection in range. */
    fun replace(items: List<Item>): Picker = copy(items = items).clamped()

    /** Handles one event. */
    fun handle(event: PickerEvent): Step =
        when (val mode = mode) {
            Mode.Filter -> filterEvent(event)
            is Mode.ConfirmDelete -> confirmEvent(mode.id, event)
            Mode.Help, is Mode.Details -> dialogEvent(event)
            Mode.Browse -> browseEvent(event)
        }

    private fun filterEvent(event: PickerEvent): Step =
        when (event) {
            is PickerEvent.FilterChanged -> stay(copy(filter = event.text, selected = 0))
            PickerEvent.KeepFilter -> stay(copy(mode = Mode.Browse))
            PickerEvent.ClearFilter, PickerEvent.Back -> stay(copy(filter = "", selected = 0, mode = Mode.Browse))
            is PickerEvent.Select -> stay(copy(selected = event.index))
            PickerEvent.Quit -> Step(this, Outcome.Quit)
            else -> stay(this)
        }

    private fun confirmEvent(
        id: String,
        event: PickerEvent,
    ): Step =
        when (event) {
            PickerEvent.ConfirmDelete -> Step(copy(mode = Mode.Browse), Outcome.Delete(id))
            PickerEvent.Quit -> Step(this, Outcome.Quit)
            else -> stay(copy(mode = Mode.Browse, status = "not deleted"))
        }

    /** Closing the help or the details never quits, even with `q`. */
    private fun dialogEvent(event: PickerEvent): Step =
        when (event) {
            is PickerEvent.Select -> stay(this)
            else -> stay(copy(mode = Mode.Browse))
        }

    private fun browseEvent(event: PickerEvent): Step =
        when (event) {
            is PickerEvent.Select -> stay(copy(selected = event.index).clamped())
            PickerEvent.StartFilter -> stay(copy(mode = Mode.Filter, status = null))
            PickerEvent.Help -> stay(copy(mode = Mode.Help))
            PickerEvent.Reload -> Step(this, Outcome.Reload)
            PickerEvent.ReloadServer -> Step(this, Outcome.ReloadServer)
            PickerEvent.Quit, PickerEvent.Back -> Step(this, Outcome.Quit)
            PickerEvent.Load -> act(Action.LOAD)
            PickerEvent.Cat -> act(Action.CAT)
            PickerEvent.Details -> withSelected { Step(this, Outcome.Details(it.id)) }
            PickerEvent.RequestDelete -> withSelected { stay(copy(mode = Mode.ConfirmDelete(it.id))) }
            else -> stay(this)
        }

    private fun act(action: Action): Step = withSelected { Step(this, Outcome.Act(action, it.id)) }

    private fun withSelected(then: (Item) -> Step): Step {
        val item = selectedItem ?: return stay(withStatus("no item selected"))
        return then(item)
    }

    private fun clamped(): Picker {
        val last = (visible.size - 1).coerceAtLeast(0)
        return copy(selected = selected.coerceIn(0, last))
    }

    private fun stay(picker: Picker): Step = Step(picker, Outcome.Continue)
}
