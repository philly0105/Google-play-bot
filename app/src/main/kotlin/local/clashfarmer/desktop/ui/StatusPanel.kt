package local.clashfarmer.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import local.clashfarmer.desktop.ui.components.FillBar
import local.clashfarmer.desktop.ui.components.hoverClick
import local.clashfarmer.desktop.ui.components.trackKeyboardNav
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerDesktopTheme
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.service.FarmerOverlay
import local.clashfarmer.service.LiveStatus
import local.clashfarmer.service.OverlayGeometry
import local.clashfarmer.ui.FarmerTheme
import java.awt.GraphicsEnvironment

/**
 * The phone's panel over Clash as a small window over the others, in the bottom right corner of
 * the main screen above the taskbar. It shows while a run is busy or a start is set, and while
 * [shown] (the tray's Status panel) holds. It never takes focus, so the window in front keeps the
 * keyboard, and it has no taskbar button. Its sizes are the phone's [OverlayGeometry] in dp.
 */
@Composable
fun StatusPanelWindow(overlay: FarmerOverlay, shown: Boolean) {
    val panel by overlay.panel.collectAsState()
    val width = if (panel.expanded) OverlayGeometry.CARD_WIDTH_DP else OverlayGeometry.PILL_WIDTH_DP
    val height = OverlayGeometry.HEADER_HEIGHT_DP + if (panel.expanded) OverlayGeometry.INFO_HEIGHT_DP else 0
    Window(
        visible = shown && panel.visible && panel.status.state != LiveStatus.State.IDLE,
        create = {
            ComposeWindow().apply {
                // A utility window has no taskbar button; the type is set before the window shows.
                type = java.awt.Window.Type.UTILITY
                isUndecorated = true
                isTransparent = true
                isResizable = false
                isAlwaysOnTop = true
                focusableWindowState = false
                isAutoRequestFocus = false
                title = "Clash Farmer status"
            }
        },
        dispose = ComposeWindow::dispose
    ) {
        LaunchedEffect(width, height) {
            val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val margin = OverlayGeometry.MARGIN_DP
            window.setBounds(area.x + area.width - width - margin, area.y + area.height - height - margin, width, height)
        }
        FarmerDesktopTheme {
            PanelContent(panel, overlay)
        }
    }
}

/** The header row (ring, title, Pause, Stop on the card), and on the card the phase, bar and last lines. */
@Composable
private fun PanelContent(panel: FarmerOverlay.Panel, overlay: FarmerOverlay) {
    val s = panel.status
    val expanded = panel.expanded
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxSize().trackKeyboardNav().clip(shape).background(FarmerColors.background).border(1.dp, FarmerColors.line, shape)) {
        Row(
            Modifier.fillMaxWidth().height(OverlayGeometry.HEADER_HEIGHT_DP.dp).padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val state = when (s.state) {
                LiveStatus.State.RUNNING -> FarmerColors.ok
                LiveStatus.State.PAUSED, LiveStatus.State.HELD -> FarmerColors.warn
                else -> FarmerColors.idle
            }
            // The ring folds the card down to the pill, which still shows progress, and opens it again.
            StatusRing(s.fraction, state, Modifier.size(28.dp).hoverClick(shape = CircleShape) {
                overlay.setCollapsed(!panel.collapsed)
            })
            Label(if (expanded) s.title else s.short, Modifier.weight(1f).padding(start = 6.dp),
                size = FarmerTheme.SMALL, medium = true, maxLines = 1)
            // The label follows the guard rather than the last press; the shade's hold keeps Pause.
            if (s.busy) {
                PauseGlyph(paused = s.state == LiveStatus.State.PAUSED, Modifier.padding(start = 4.dp).size(32.dp)) {
                    overlay.pressPause()
                }
            }
            if (expanded) {
                val stopShape = RoundedCornerShape(FarmerSize.BUTTON_RADIUS)
                Box(
                    Modifier.padding(start = 4.dp).size(width = 52.dp, height = 32.dp).clip(stopShape)
                        .background(FarmerColors.bad).hoverClick(shape = stopShape) { overlay.pressStop() },
                    contentAlignment = Alignment.Center
                ) {
                    Label("Stop", size = 13f, medium = true, maxLines = 1)
                }
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp)) {
                Label(panel.phase, Modifier.padding(top = 2.dp), size = FarmerTheme.SMALL, maxLines = 1)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val fill = s.fraction.takeIf { !s.waiting }
                    if (fill != null) FillBar(fill, FarmerColors.gold, Modifier.weight(1f).height(6.dp).padding(end = 8.dp))
                    Label(s.meta, size = FarmerTheme.LABEL, color = FarmerColors.muted, maxLines = 1, tabular = true)
                }
                Label(panel.last, Modifier.padding(top = 6.dp), size = FarmerTheme.LABEL, color = FarmerColors.muted, maxLines = 1)
            }
        }
    }
}

/** The phone's StatusBubble: a gold progress ring around a dot in the run state's colour. */
@Composable
private fun StatusRing(progress: Float?, state: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val side = minOf(size.width, size.height) - stroke
        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        val arc = Size(side, side)
        drawArc(FarmerColors.withAlpha(FarmerColors.gold, 60), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
        val sweep = (progress ?: 0f).coerceIn(0f, 1f) * 360f
        if (sweep > 0f) {
            drawArc(FarmerColors.gold, -90f, sweep, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        drawCircle(state, radius = minOf(size.width, size.height) * .17f)
    }
}

/** The phone's PauseGlyph: two bars for Pause, a triangle for Resume, on a gold slab. */
@Composable
private fun PauseGlyph(paused: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FarmerSize.BUTTON_RADIUS)
    Canvas(modifier.clip(shape).background(FarmerColors.gold).hoverClick(shape = shape, onClick = onClick)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val h = size.height * .4f
        val ink = FarmerColors.background
        if (paused) {
            val triangle = Path().apply {
                moveTo(cx - h * .4f, cy - h / 2)
                lineTo(cx + h * .5f, cy)
                lineTo(cx - h * .4f, cy + h / 2)
                close()
            }
            drawPath(triangle, ink)
        } else {
            val bar = h * .26f
            val gap = h * .14f
            drawRect(ink, Offset(cx - gap - bar, cy - h / 2), Size(bar, h))
            drawRect(ink, Offset(cx + gap, cy - h / 2), Size(bar, h))
        }
    }
}
