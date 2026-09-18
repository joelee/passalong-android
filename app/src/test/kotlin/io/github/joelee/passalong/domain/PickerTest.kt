package io.github.joelee.passalong.domain

import io.github.joelee.passalong.support.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the TUI's `choose/state.rs` tests, event for key. */
class PickerTest {
    private fun Picker.after(vararg events: PickerEvent): Picker = events.fold(this) { p, e -> p.handle(e).picker }

    private fun Picker.typed(text: String): Picker = after(PickerEvent.FilterChanged(text))

    @Test
    fun `selecting stays within the list`() {
        val items = Samples.texts("a", "b", "c")
        var picker = Picker(items)
        assertEquals("newest first", items[0].id, picker.selectedItem?.id)
        picker = picker.after(PickerEvent.Select(5))
        assertEquals(2, picker.selected)
        picker = picker.after(PickerEvent.Select(-1))
        assertEquals(0, picker.selected)
        picker = picker.after(PickerEvent.Select(1))
        assertEquals(items[1].id, picker.selectedItem?.id)
    }

    @Test
    fun `the filter matches case insensitively until kept or cleared`() {
        val items = Samples.texts("Alpha one", "beta two", "gamma")
        var picker = Picker(items).after(PickerEvent.Select(1))
        var step = picker.handle(PickerEvent.StartFilter)
        assertEquals(Outcome.Continue, step.outcome)
        picker = step.picker
        assertEquals(Mode.Filter, picker.mode)
        picker = picker.typed("ALPx")
        assertTrue(picker.visible.isEmpty())
        assertNull(picker.selectedItem)
        picker = picker.typed("ALP")
        assertEquals(1, picker.visible.size)
        assertEquals("a new filter starts at the top", 0, picker.selected)
        // Actions are not taken while filtering.
        assertEquals(Outcome.Continue, picker.handle(PickerEvent.Load).outcome)
        step = picker.handle(PickerEvent.KeepFilter)
        picker = step.picker
        assertEquals(Mode.Browse, picker.mode)
        assertEquals("Enter keeps the filter", "ALP", picker.filter)
        assertEquals(1, picker.visible.size)
        picker = picker.after(PickerEvent.StartFilter, PickerEvent.ClearFilter)
        assertEquals(Mode.Browse, picker.mode)
        assertEquals("Esc clears the filter", "", picker.filter)
        assertEquals(3, picker.visible.size)
        // Back while filtering clears it too, without quitting.
        step = picker.after(PickerEvent.StartFilter).typed("x").handle(PickerEvent.Back)
        assertEquals(Outcome.Continue, step.outcome)
        assertEquals("", step.picker.filter)
        // The device, the kind, and the id match too.
        for (text in listOf("box", "TEXT")) {
            assertEquals(text, 3, picker.after(PickerEvent.StartFilter).typed(text).visible.size)
        }
        val id = picker.visible[2].id
        assertEquals(1, picker.after(PickerEvent.StartFilter).typed(id).visible.size)
        // Selecting a row while filtering works, and quitting is still possible.
        assertEquals(1, picker.after(PickerEvent.StartFilter, PickerEvent.Select(1)).selected)
        assertEquals(Outcome.Quit, picker.after(PickerEvent.StartFilter).handle(PickerEvent.Quit).outcome)
    }

    @Test
    fun `load and cat choose an action and details asks for them`() {
        val items = Samples.texts("a", "b")
        val picker = Picker(items).after(PickerEvent.Select(1))
        val id = items[1].id
        assertEquals(Outcome.Act(Action.LOAD, id), picker.handle(PickerEvent.Load).outcome)
        assertEquals(Outcome.Act(Action.CAT, id), picker.handle(PickerEvent.Cat).outcome)
        assertEquals(Outcome.Details(id), picker.handle(PickerEvent.Details).outcome)
        val empty = picker.after(PickerEvent.StartFilter).typed("nothing matches").after(PickerEvent.KeepFilter)
        for (event in listOf(PickerEvent.Load, PickerEvent.Cat, PickerEvent.Details, PickerEvent.RequestDelete)) {
            val step = empty.handle(event)
            assertEquals(Outcome.Continue, step.outcome)
            assertEquals("no item selected", step.picker.status)
            assertEquals(Mode.Browse, step.picker.mode)
        }
    }

    @Test
    fun `delete asks and only confirming deletes`() {
        val items = Samples.texts("a")
        val id = items[0].id
        var step = Picker(items).handle(PickerEvent.RequestDelete)
        assertEquals(Outcome.Continue, step.outcome)
        assertEquals(Mode.ConfirmDelete(id), step.picker.mode)
        step = step.picker.handle(PickerEvent.CancelDelete)
        assertEquals(Outcome.Continue, step.outcome)
        assertEquals(Mode.Browse, step.picker.mode)
        assertEquals("not deleted", step.picker.status)
        step = step.picker.after(PickerEvent.RequestDelete).handle(PickerEvent.Back)
        assertEquals("not deleted", step.picker.status)
        step = step.picker.after(PickerEvent.RequestDelete).handle(PickerEvent.ConfirmDelete)
        assertEquals(Outcome.Delete(id), step.outcome)
        assertEquals(Mode.Browse, step.picker.mode)
        assertEquals(Outcome.Quit, step.picker.after(PickerEvent.RequestDelete).handle(PickerEvent.Quit).outcome)
    }

    @Test
    fun `reload, quit, and back from the list`() {
        val picker = Picker(Samples.texts("a"))
        assertEquals(Outcome.Reload, picker.handle(PickerEvent.Reload).outcome)
        assertEquals(Outcome.ReloadServer, picker.handle(PickerEvent.ReloadServer).outcome)
        assertEquals(Outcome.Quit, picker.handle(PickerEvent.Quit).outcome)
        assertEquals(Outcome.Quit, picker.handle(PickerEvent.Back).outcome)
        // Events that mean nothing in the list are ignored.
        for (event in listOf(
            PickerEvent.KeepFilter,
            PickerEvent.ClearFilter,
            PickerEvent.ConfirmDelete,
            PickerEvent.CancelDelete,
        )) {
            val step = picker.handle(event)
            assertEquals(Outcome.Continue, step.outcome)
            assertEquals(picker, step.picker)
        }
        assertEquals(PickerEvent.FilterChanged("x"), PickerEvent.FilterChanged("x"))
    }

    @Test
    fun `help opens and closing it never quits`() {
        var step = Picker(Samples.texts("a")).handle(PickerEvent.Help)
        assertEquals(Outcome.Continue, step.outcome)
        assertEquals(Mode.Help, step.picker.mode)
        step = step.picker.handle(PickerEvent.Quit)
        assertEquals("closing help does not quit, even with q", Outcome.Continue, step.outcome)
        assertEquals(Mode.Browse, step.picker.mode)
        step = step.picker.after(PickerEvent.Help).handle(PickerEvent.Load)
        assertEquals("Enter does not load", Outcome.Continue, step.outcome)
        assertEquals(Mode.Browse, step.picker.mode)
        assertEquals(Mode.Browse, step.picker.after(PickerEvent.Help, PickerEvent.Back).mode)
        // Selecting a row under the dialog changes nothing.
        val help = step.picker.after(PickerEvent.Help)
        assertEquals(help, help.after(PickerEvent.Select(0)))
    }

    @Test
    fun `details show over the list and close without quitting`() {
        val picker = Picker(Samples.texts("a"))
        val lines = (0 until 30).map { "row $it" }
        val details = picker.showDetails("title", lines)
        assertEquals(Mode.Details("title", lines), details.mode)
        // Other events do nothing while the details are open, except closing.
        assertEquals(Outcome.Continue, details.handle(PickerEvent.Reload).outcome)
        assertEquals(Mode.Browse, details.handle(PickerEvent.Reload).picker.mode)
        for (close in listOf(PickerEvent.Back, PickerEvent.Quit, PickerEvent.Details, PickerEvent.Load)) {
            val step = details.handle(close)
            assertEquals(Outcome.Continue, step.outcome)
            assertEquals(Mode.Browse, step.picker.mode)
        }
        assertEquals(details, details.after(PickerEvent.Select(0)))
        assertNull(picker.withStatus("Reloading...").clearStatus().status)
    }

    @Test
    fun `the selection stays in range as items go`() {
        val items = Samples.texts("a", "b", "c")
        var picker = Picker(items).after(PickerEvent.Select(2))
        picker = picker.replace(items.take(2))
        assertEquals(items[1].id, picker.selectedItem?.id)
        assertEquals(2, picker.visible.size)
        picker = picker.replace(items.take(1))
        assertEquals(items[0].id, picker.selectedItem?.id)
        picker = picker.replace(emptyList())
        assertNull(picker.selectedItem)
        assertEquals(0, picker.total)
        picker = picker.withStatus("reloaded")
        assertEquals("reloaded", picker.status)
    }
}
