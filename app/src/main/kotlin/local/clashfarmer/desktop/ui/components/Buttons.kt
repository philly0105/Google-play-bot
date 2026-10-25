package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme

/**
 * Click handling with the desktop's feedback: a hand cursor, a light wash on hover and a
 * stronger one while pressed. Disabled, it ignores clicks and shows neither. Tab reaches it,
 * Enter or Space clicks it, and a gold ring shows while the keyboard is on it: not after a
 * click, which focuses it too ([KeyboardNav]).
 */
@Composable
fun Modifier.hoverClick(enabled: Boolean = true, shape: Shape = RectangleShape, onClick: () -> Unit): Modifier =
    hoverClick(enabled, shape, FarmerColors.gold, onClick)

/** [hoverClick] with the focus ring in [ring], for a control that is gold itself. */
@Composable
fun Modifier.hoverClick(enabled: Boolean, shape: Shape, ring: Color, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val wash = when {
        !enabled -> Color.Transparent
        pressed -> Color.White.copy(alpha = 0.14f)
        hovered -> Color.White.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    return this
        .clip(shape)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
        .then(if (focused && enabled && KeyboardNav.active) Modifier.border(FOCUS_RING, ring, shape) else Modifier)
        .drawWithContent {
            drawContent()
            if (wash != Color.Transparent) drawRect(wash)
        }
}

/** The keyboard focus ring's width, on every control that takes focus. */
val FOCUS_RING: Dp = 2.dp

/**
 * The three button kinds share one shape: 16 dp radius, medium text, 16 dp side padding. A
 * disabled button fades, except where [disabledFill] is set: the main action then draws in that
 * fill at full strength, so its label stays readable beside the reason it is off.
 */
@Composable
private fun FarmerButton(
    text: String,
    fill: Color,
    ink: Color,
    border: Color?,
    size: Float,
    minHeight: Dp,
    enabled: Boolean,
    modifier: Modifier,
    disabledFill: Color? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(FarmerSize.BUTTON_RADIUS)
    val solid = enabled || disabledFill != null
    Box(
        modifier = modifier
            .alpha(if (solid) 1f else 0.45f)
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(if (enabled) fill else disabledFill ?: fill)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .hoverClick(enabled, shape, if (fill == FarmerColors.gold) FarmerColors.text else FarmerColors.gold, onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Label(text, size = size, color = if (enabled) ink else FarmerColors.muted, medium = true, maxLines = 1,
            align = TextAlign.Center)
    }
}

/** Gold with dark ink: the screen's one main action (spec 1, principle 2). */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) =
    FarmerButton(text, FarmerColors.gold, FarmerColors.background, null, if (compact) FarmerTheme.SMALL + 1 else 16f,
        if (compact) FarmerSize.CONTROL else FarmerSize.PRIMARY, enabled, modifier, FarmerColors.surfaceStrong, onClick)

/** Outlined glass: everything that is not the main action. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) =
    FarmerButton(text, FarmerColors.surfaceStrong, FarmerColors.text, FarmerColors.line,
        if (compact) FarmerTheme.SMALL + 1 else FarmerTheme.BODY, FarmerSize.CONTROL, enabled, modifier, onClick = onClick)

/** Red: Stop. */
@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) =
    FarmerButton(text, FarmerColors.bad, FarmerColors.text, null, if (compact) FarmerTheme.SMALL + 1 else 16f,
        FarmerSize.CONTROL, enabled, modifier, onClick = onClick)

/** A gold text link such as Home's "Change run >": no slab, the hover wash only. */
@Composable
fun TextLink(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = FarmerColors.gold, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .hoverClick(enabled, RoundedCornerShape(8.dp), onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Label(text, size = FarmerTheme.SMALL + 1, color = if (enabled) color else FarmerColors.faint, medium = true, maxLines = 1)
    }
}
