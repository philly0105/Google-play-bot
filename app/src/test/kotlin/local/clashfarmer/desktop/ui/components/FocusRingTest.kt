package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerDesktopTheme
import org.jetbrains.skia.Bitmap
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A click focuses a button on the desktop, but its focus ring shows only once the keyboard is used. */
// The key event factory is marked internal; Compose's version is pinned, so a test may use it.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FocusRingTest {

    @After
    fun reset() {
        KeyboardNav.active = false
    }

    @Test
    fun `a clicked button shows no ring until a key is pressed`() {
        KeyboardNav.active = true
        var clicks = 0
        val scene = ImageComposeScene(300, 120, Density(1f)) {
            FarmerDesktopTheme {
                Box(Modifier.fillMaxSize().trackKeyboardNav()) {
                    SecondaryButton("Open", Modifier.offset(20.dp, 20.dp).size(200.dp, 48.dp)) { clicks++ }
                }
            }
        }
        try {
            var frame = 0L
            fun frames() = repeat(3) { scene.render(++frame * 16_000_000L) }
            frames()
            val centre = Offset(120f, 44f)
            scene.sendPointerEvent(PointerEventType.Press, centre, buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, centre, button = PointerButton.Primary)
            frames()
            assertTrue("the click did not land", clicks == 1)
            assertFalse(KeyboardNav.active)
            assertFalse("a ring shows after a click", ringAt(scene, frame))

            scene.sendKeyEvent(KeyEvent(Key.ShiftLeft, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.ShiftLeft, KeyEventType.KeyUp))
            frames()
            assertTrue(KeyboardNav.active)
            assertTrue("no ring once the keyboard is used", ringAt(scene, frame + 1))
        } finally {
            scene.close()
        }
    }

    /** Whether the middle of the button's top edge is drawn in the ring's gold. */
    private fun ringAt(scene: ImageComposeScene, frame: Long): Boolean {
        val bitmap = Bitmap.makeFromImage(scene.render(frame * 16_000_000L))
        val pixel = bitmap.getColor(120, 21)
        val gold = FarmerColors.gold.toArgb()
        return (0..2).all { shift ->
            val channel = 8 * shift
            abs((pixel shr channel and 0xFF) - (gold shr channel and 0xFF)) <= 24
        }
    }
}
