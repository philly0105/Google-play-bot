package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme

/**
 * [-] value [+]. Holding a button repeats the step every 90 ms after a 400 ms wait. The caller
 * owns [value]: [onChange] reports each step and the next composition shows it.
 */
@Composable
fun Stepper(
    value: Int,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit
) {
    val shown = value.coerceIn(min, max)
    val current by rememberUpdatedState(shown)
    val report by rememberUpdatedState(onChange)

    /** One step in [direction]; false at the bound, which ends a held repeat. */
    fun nudge(direction: Int): Boolean {
        val next = (current + direction * step).coerceIn(min, max)
        if (next == current) return false
        report(next)
        return true
    }

    // A disabled stepper fades as a whole; its buttons then keep full alpha so it fades only once.
    Row(modifier.alpha(if (enabled) 1f else .45f), verticalAlignment = Alignment.CenterVertically) {
        StepButton("-", "Decrease", enabled && shown > min, faded = enabled && shown <= min) { nudge(-1) }
        Label(format(shown), Modifier.weight(1f), size = FarmerTheme.TITLE, bold = true, maxLines = 1, align = TextAlign.Center)
        StepButton("+", "Increase", enabled && shown < max, faded = enabled && shown >= max) { nudge(1) }
    }
}

/** A 56 dp glass button that steps on press and keeps stepping while held. */
@Composable
private fun StepButton(symbol: String, description: String, usable: Boolean, faded: Boolean, nudge: () -> Boolean) {
    val shape = RoundedCornerShape(FarmerSize.BUTTON_RADIUS)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var pressed by remember { mutableStateOf(false) }
    val canStep by rememberUpdatedState(usable)
    val step by rememberUpdatedState(nudge)
    val wash = when {
        !usable -> Color.Transparent
        pressed -> Color.White.copy(alpha = 0.14f)
        hovered -> Color.White.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = FarmerSize.CONTROL)
            .semantics { contentDescription = description }
            .alpha(if (faded) .45f else 1f)
            .clip(shape)
            .background(FarmerColors.surfaceStrong)
            .background(wash)
            .border(1.dp, FarmerColors.line, shape)
            .hoverable(source)
            .then(if (usable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (canStep) {
                        pressed = true
                        coroutineScope {
                            val repeat = launch {
                                if (step()) {
                                    delay(400)
                                    while (step()) delay(90)
                                }
                            }
                            tryAwaitRelease()
                            repeat.cancel()
                        }
                        pressed = false
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Label(symbol, size = 22f, bold = true, maxLines = 1)
    }
}
