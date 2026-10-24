package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.edgeColor
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.Tone

/** The widest a page's column grows: past it, a wide window centres the column instead. */
val PAGE_MAX_WIDTH: Dp = 880.dp

/**
 * Page scaffolding every tab shares: a vertical scroller that fills the tab, the mouse wheel and
 * a scrollbar to move it, 16 dp gutters, and a centred column no wider than [PAGE_MAX_WIDTH].
 */
@Composable
fun ScreenPage(
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = PAGE_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = FarmerSize.GUTTER, top = 12.dp, end = FarmerSize.GUTTER, bottom = 24.dp),
                content = content
            )
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp)
        )
    }
}

/** "Setup" in 28 sp with an optional muted subtitle under it, then 16 dp of space. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Label(title, size = FarmerTheme.HEADLINE, bold = true)
    if (subtitle != null) {
        Label(subtitle, Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
    }
    Spacer(Modifier.height(16.dp))
}

/** FarmerTheme.card: surface fill, hairline border, 20 dp radius, 16 dp padding, 12 dp below. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: Dp = FarmerSize.GUTTER,
    bottom: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(FarmerSize.CARD_RADIUS)
    Column(
        modifier = modifier
            .padding(bottom = bottom)
            .fillMaxWidth()
            .clip(shape)
            .background(FarmerColors.surface)
            .border(1.dp, FarmerColors.line, shape)
            .padding(padding),
        content = content
    )
}

/** A card whose fill and border carry [tone]: the attention style. */
@Composable
fun ToneCard(tone: Tone, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(FarmerSize.CARD_RADIUS)
    Column(
        modifier = modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color(FarmerTheme.withAlpha(tone.color, 26)))
            .border(1.dp, tone.edgeColor, shape)
            .padding(FarmerSize.GUTTER),
        content = content
    )
}

/** A thin full-width hairline. */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FarmerColors.line))
}

/** Vertical space, FarmerTheme.space. */
@Composable
fun Gap(height: Dp) {
    Spacer(Modifier.height(height))
}
