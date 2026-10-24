package local.clashfarmer.desktop.ui.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.clashfarmer.ui.FarmerTheme

/**
 * Desktop sizes for the phone's touch tokens. FarmerTheme.TOUCH (48 dp) is a finger target; a
 * mouse needs less, so controls are 40 dp tall here and the main action 44 dp.
 */
object FarmerSize {
    val GUTTER: Dp = FarmerTheme.GUTTER.dp
    val CONTROL: Dp = 40.dp
    val PRIMARY: Dp = 44.dp
    val CARD_RADIUS: Dp = FarmerTheme.CARD_RADIUS.dp
    val BUTTON_RADIUS: Dp = FarmerTheme.BUTTON_RADIUS.dp
}

/**
 * The text style FarmerTheme.label built: [size] in sp from the type scale, 3 dp of extra line
 * spacing, regular weight unless [bold] or [medium].
 */
fun farmerStyle(
    size: Float = FarmerTheme.BODY,
    color: Color = FarmerColors.text,
    bold: Boolean = false,
    medium: Boolean = false,
    align: TextAlign = TextAlign.Start,
    tabular: Boolean = false,
    monospace: Boolean = false,
    letterSpacing: Float = 0f
): TextStyle = TextStyle(
    color = color,
    fontSize = size.sp,
    fontWeight = when {
        bold -> FontWeight.Bold
        medium -> FontWeight.Medium
        else -> FontWeight.Normal
    },
    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
    lineHeight = (size * 1.2f + 3f).sp,
    letterSpacing = (letterSpacing * size).sp,
    textAlign = align,
    fontFeatureSettings = if (tabular) "tnum" else null
)

/** FarmerTheme.label and FarmerTheme.medium in one: a line of themed text. */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: Float = FarmerTheme.BODY,
    color: Color = FarmerColors.text,
    bold: Boolean = false,
    medium: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
    tabular: Boolean = false,
    monospace: Boolean = false,
    letterSpacing: Float = 0f
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = farmerStyle(size, color, bold, medium, align, tabular, monospace, letterSpacing),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines
    )
}

/**
 * The window's theme: the dark green background is drawn by the caller; this sets the scrollbar
 * and selection colours for a dark surface, which the desktop defaults (dark on light) are not.
 */
@Composable
fun FarmerDesktopTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalScrollbarStyle provides defaultScrollbarStyle().copy(
            unhoverColor = Color.White.copy(alpha = 0.16f),
            hoverColor = Color.White.copy(alpha = 0.36f)
        ),
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = FarmerColors.gold,
            backgroundColor = FarmerColors.gold.copy(alpha = 0.35f)
        ),
        content = content
    )
}
