package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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

/** A form's column (Run, the account editor): narrower, so tracks and steppers keep a sensible length. */
val FORM_MAX_WIDTH: Dp = 640.dp

/**
 * Page scaffolding every tab shares: a vertical scroller that fills the tab, the mouse wheel and
 * a scrollbar to move it, 16 dp gutters, and a centred column no wider than [maxWidth].
 */
@Composable
fun ScreenPage(
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    maxWidth: Dp = PAGE_MAX_WIDTH,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
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

/**
 * "Setup" in 28 sp with an optional muted subtitle under it, then 16 dp of space. [action], such
 * as Stats' Export CSV, sits at the title's right.
 */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    if (action == null) {
        Label(title, size = FarmerTheme.HEADLINE, bold = true)
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(title, Modifier.weight(1f), size = FarmerTheme.HEADLINE, bold = true)
            action()
        }
    }
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

/**
 * A footer that stays under a scrolling page, such as Run's Start: a hairline on top and the
 * window's fill, so the page scrolls under a clear edge, then a centred column [maxWidth] wide.
 */
@Composable
fun StickyFooter(maxWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(FarmerColors.background)) {
        Divider()
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .padding(start = FarmerSize.GUTTER, top = 12.dp, end = FarmerSize.GUTTER, bottom = 12.dp),
                content = content
            )
        }
    }
}

/**
 * Why Start is off, in amber, with [fix], a link to where it is put right, at its right when
 * there is one.
 */
@Composable
fun StartReason(reason: String, fix: String?, modifier: Modifier = Modifier, onFix: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(reason, Modifier.weight(1f), size = FarmerTheme.SMALL, color = FarmerColors.warn, maxLines = 2)
        if (fix != null) TextLink(fix, Modifier.padding(start = 8.dp), onClick = onFix)
    }
}

/** A hover tooltip's box, for a TooltipArea: the rail's fill, a hairline and small text. */
@Composable
fun Tooltip(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .background(FarmerColors.navBar)
            .border(1.dp, FarmerColors.line, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Label(text, size = FarmerTheme.SMALL, maxLines = 1)
    }
}

/** Vertical space, FarmerTheme.space. */
@Composable
fun Gap(height: Dp) {
    Spacer(Modifier.height(height))
}
