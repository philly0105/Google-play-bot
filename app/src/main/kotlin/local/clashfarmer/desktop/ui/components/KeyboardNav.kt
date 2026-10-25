package local.clashfarmer.desktop.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Whether the keyboard was used last. On the desktop a mouse click focuses what it clicks, so a
 * focus ring shown for every focus would stay on the last control clicked. The ring shows only
 * while this is set: a key press sets it and a mouse press clears it, both seen by
 * [trackKeyboardNav] on each window's or dialog's root.
 */
object KeyboardNav {
    var active by mutableStateOf(false)
}

/** Keeps [KeyboardNav] current for the keys and mouse presses that reach this node or anything inside it. */
fun Modifier.trackKeyboardNav(): Modifier = this
    .onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown) KeyboardNav.active = true
        false
    }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // The first pass, so it is cleared before the clicked control takes focus.
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) KeyboardNav.active = false
            }
        }
    }
