package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.farmerStyle
import local.clashfarmer.ui.FarmerTheme

/**
 * The phone's EditText as a desktop field: one line on the inset fill, a hairline border that
 * turns gold while the field has focus (red with [error]), a faint [hint] while empty, and at most
 * [maxLength] characters. Enter calls [onSubmit] when one is set.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    maxLength: Int = Int.MAX_VALUE,
    error: Boolean = false,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = { next -> if (next.length <= maxLength) onValueChange(next) },
        modifier = modifier.fillMaxWidth().submitOnEnter(onSubmit),
        enabled = enabled,
        textStyle = farmerStyle(FarmerTheme.BODY),
        singleLine = true,
        interactionSource = source,
        cursorBrush = SolidColor(FarmerColors.gold),
        decorationBox = { field -> InputFrame(value.isEmpty(), hint, error, focused, enabled, field) }
    )
}

/** [TextInput] over a [TextFieldValue], for a field that opens with its text selected. */
@Composable
fun TextInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    maxLength: Int = Int.MAX_VALUE,
    error: Boolean = false,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = { next -> if (next.text.length <= maxLength) onValueChange(next) },
        modifier = modifier.fillMaxWidth().submitOnEnter(onSubmit),
        enabled = enabled,
        textStyle = farmerStyle(FarmerTheme.BODY),
        singleLine = true,
        interactionSource = source,
        cursorBrush = SolidColor(FarmerColors.gold),
        decorationBox = { field -> InputFrame(value.text.isEmpty(), hint, error, focused, enabled, field) }
    )
}

/** Enter and the keypad's Enter call [onSubmit]; other keys reach the field. */
private fun Modifier.submitOnEnter(onSubmit: (() -> Unit)?): Modifier =
    if (onSubmit == null) this
    else onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
            onSubmit()
            true
        } else {
            false
        }
    }

/** The field's box: FarmerTheme.shape(inset, 12, line) at the desktop control height. */
@Composable
private fun InputFrame(
    empty: Boolean,
    hint: String,
    error: Boolean,
    focused: Boolean,
    enabled: Boolean,
    field: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .defaultMinSize(minHeight = FarmerSize.CONTROL)
            .clip(shape)
            .background(FarmerColors.inset)
            .border(1.dp, when {
                error -> FarmerColors.bad
                focused -> FarmerColors.gold
                else -> FarmerColors.line
            }, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (empty && hint.isNotEmpty()) Label(hint, size = FarmerTheme.BODY, color = FarmerColors.faint, maxLines = 1)
        field()
    }
}
