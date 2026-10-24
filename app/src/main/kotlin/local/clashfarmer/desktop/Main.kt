package local.clashfarmer.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Placeholder until the desktop shell lands: one empty window. */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Clash Farmer PC") {}
}
