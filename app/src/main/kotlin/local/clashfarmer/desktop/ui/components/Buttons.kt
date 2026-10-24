package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
 * stronger one while pressed. Disabled, it ignores clicks and shows neither.
 */
@Composable
fun Modifier.hoverClick(enabled: Boolean = true, shape: Shape = RectangleShape, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
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
        .drawWithContent {
            drawContent()
            if (wash != Color.Transparent) drawRect(wash)
        }
}

/** The three button kinds share one shape: 16 dp radius, medium text, 16 dp side padding. */
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
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(FarmerSize.BUTTON_RADIUS)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .hoverClick(enabled, shape, onClick)
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
        if (compact) FarmerSize.CONTROL else FarmerSize.PRIMARY, enabled, modifier, onClick)

/** Outlined glass: everything that is not the main action. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) =
    FarmerButton(text, FarmerColors.surfaceStrong, FarmerColors.text, FarmerColors.line,
        if (compact) FarmerTheme.SMALL + 1 else FarmerTheme.BODY, FarmerSize.CONTROL, enabled, modifier, onClick)

/** Red: Stop. */
@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) =
    FarmerButton(text, FarmerColors.bad, FarmerColors.text, null, if (compact) FarmerTheme.SMALL + 1 else 16f,
        FarmerSize.CONTROL, enabled, modifier, onClick)

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
