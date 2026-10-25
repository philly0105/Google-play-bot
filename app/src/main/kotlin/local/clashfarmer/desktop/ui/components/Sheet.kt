package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme
import java.util.Locale

/** How wide a sheet grows in a wide window. */
val SHEET_WIDTH: Dp = 680.dp

/**
 * The phone's full-screen Sheet as a desktop dialog: a title row with Close (and [actions] such
 * as Save, left of Close) over a scrolling body. Escape closes it like the phone's Back did; a
 * click outside does not, so an editor never loses its edits to a stray click. Show it by
 * composing it; [onClose] must stop composing it.
 */
@Composable
fun Sheet(
    title: String,
    onClose: () -> Unit,
    width: Dp = SHEET_WIDTH,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Panel(width) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp)
                    .padding(start = FarmerSize.GUTTER, top = 8.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Label(title, Modifier.weight(1f), size = FarmerTheme.TITLE, bold = true, maxLines = 1)
                actions()
                SecondaryButton("Close", compact = true, onClick = onClose)
            }
            Divider()
            ScrollBody(Modifier.weight(1f, fill = false)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = FarmerSize.GUTTER, top = 4.dp, end = FarmerSize.GUTTER, bottom = 24.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * The phone's AlertDialog: a title, a message the user can select and copy, and one or two
 * buttons. [dismiss] null leaves only [confirm]; Escape and a click outside call [onDismiss],
 * unless [buttonsOnly], for a choice whose dismiss button does something drastic, such as quit.
 * [monospace] sets the message in 12 sp monospace, as Inspect's report was.
 */
@Composable
fun MessageDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String? = null,
    danger: Boolean = false,
    monospace: Boolean = false,
    buttonsOnly: Boolean = false
) {
    Dialog(
        onDismissRequest = if (buttonsOnly) ({}) else onDismiss,
        properties = DialogProperties(dismissOnClickOutside = !buttonsOnly, usePlatformDefaultWidth = false)
    ) {
        Panel(if (monospace) SHEET_WIDTH else 460.dp) {
            Label(title, Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp), size = FarmerTheme.TITLE, bold = true)
            ScrollBody(Modifier.weight(1f, fill = false)) {
                SelectionContainer {
                    Label(message, Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 4.dp),
                        size = if (monospace) 12f else FarmerTheme.BODY,
                        color = if (monospace) FarmerColors.text else FarmerColors.muted, monospace = monospace)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (dismiss != null) SecondaryButton(dismiss, compact = true, onClick = onDismiss)
                if (danger) DangerButton(confirm, compact = true, onClick = onConfirm)
                else PrimaryButton(confirm, compact = true, onClick = onConfirm)
            }
        }
    }
}

/**
 * The phone's 24-hour TimePickerDialog: the time as HH:MM to type, with hour and minute steppers
 * for the mouse. Enter or Set calls [onSet]; Escape, a click outside and Cancel call [onDismiss].
 * Show it by composing it; both callbacks must stop composing it.
 */
@Composable
fun TimeDialog(title: String, hour: Int, minute: Int, onSet: (hour: Int, minute: Int) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(hour.coerceIn(0, 23) to minute.coerceIn(0, 59)) }
    var text by remember { mutableStateOf(clockValue(picked)) }
    val valid = parseClock(text.text) != null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Typing replaces the time at once, as in a desktop time field.
        runCatching { focus.requestFocus() }
    }
    fun pick(next: Pair<Int, Int>) {
        picked = next
        text = clockValue(next)
    }
    fun confirm() {
        val time = parseClock(text.text) ?: return
        onSet(time.first, time.second)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(380.dp) {
            Label(title, Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp), size = FarmerTheme.TITLE, bold = true)
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, end = 20.dp)) {
                TextInput(
                    value = text,
                    onValueChange = { next ->
                        text = next
                        parseClock(next.text)?.let { picked = it }
                    },
                    modifier = Modifier.focusRequester(focus),
                    hint = "HH:MM",
                    maxLength = 5,
                    error = !valid,
                    onSubmit = ::confirm
                )
                Label(
                    if (valid) "24-hour clock" else "Type the time as HH:MM, 00:00 to 23:59",
                    Modifier.padding(top = 6.dp),
                    size = FarmerTheme.SMALL,
                    color = if (valid) FarmerColors.muted else FarmerColors.bad
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label("Hour", Modifier.padding(bottom = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted)
                        Stepper(picked.first, 0, 23, format = ::twoDigits) { pick(it to picked.second) }
                    }
                    Column(Modifier.weight(1f)) {
                        Label("Minute", Modifier.padding(bottom = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted)
                        Stepper(picked.second, 0, 59, format = ::twoDigits) { pick(picked.first to it) }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                SecondaryButton("Cancel", compact = true, onClick = onDismiss)
                PrimaryButton("Set", enabled = valid, compact = true, onClick = ::confirm)
            }
        }
    }
}

/** "8:30", "08:30", "0830" or "8.30" as hour and minute on a 24-hour clock; null for anything else. */
internal fun parseClock(text: String): Pair<Int, Int>? {
    val match = CLOCK.matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    return if (hour in 0..23 && minute in 0..59) hour to minute else null
}

private val CLOCK = Regex("""(\d{1,2})[:.]?(\d{2})""")

private fun twoDigits(n: Int): String = String.format(Locale.US, "%02d", n)

/** The time as HH:MM, all of it selected so typing replaces it. */
private fun clockValue(time: Pair<Int, Int>): TextFieldValue {
    val text = "${twoDigits(time.first)}:${twoDigits(time.second)}"
    return TextFieldValue(text, TextRange(0, text.length))
}

/** The dialog card: background colour, hairline border, card radius, at most [width] wide. */
@Composable
private fun Panel(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(FarmerSize.CARD_RADIUS)
    Column(
        modifier = Modifier
            .padding(24.dp)
            .widthIn(max = width)
            .fillMaxWidth()
            .trackKeyboardNav()
            .clip(shape)
            .background(FarmerColors.background)
            .border(1.dp, FarmerColors.line, shape),
        content = content
    )
}

/**
 * A vertical scroller with a scrollbar that is only as tall as the body: the bar sits in a box
 * matched to the content, so a short body leaves the dialog short.
 */
@Composable
private fun ScrollBody(modifier: Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Box(Modifier.verticalScroll(scroll)) { content() }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp)
            )
        }
    }
}
