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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme

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
 * buttons. [dismiss] null leaves only [confirm]; Escape and a click outside call [onDismiss].
 */
@Composable
fun MessageDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String? = null,
    danger: Boolean = false
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(460.dp) {
            Label(title, Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp), size = FarmerTheme.TITLE, bold = true)
            ScrollBody(Modifier.weight(1f, fill = false)) {
                SelectionContainer {
                    Label(message, Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 4.dp),
                        size = FarmerTheme.BODY, color = FarmerColors.muted)
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

/** The dialog card: background colour, hairline border, card radius, at most [width] wide. */
@Composable
private fun Panel(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(FarmerSize.CARD_RADIUS)
    Column(
        modifier = Modifier
            .padding(24.dp)
            .widthIn(max = width)
            .fillMaxWidth()
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
