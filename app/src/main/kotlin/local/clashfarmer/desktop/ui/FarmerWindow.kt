package local.clashfarmer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import local.clashfarmer.desktop.ui.calibration.CalibrationScreen
import local.clashfarmer.desktop.ui.components.DangerButton
import local.clashfarmer.desktop.ui.components.Divider
import local.clashfarmer.desktop.ui.components.Dot
import local.clashfarmer.desktop.ui.components.MessageDialog
import local.clashfarmer.desktop.ui.components.SecondaryButton
import local.clashfarmer.desktop.ui.components.TabIcon
import local.clashfarmer.desktop.ui.components.hoverClick
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerDesktopTheme
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.farmerIconPainter
import local.clashfarmer.desktop.ui.theme.ink
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.Tab
import local.clashfarmer.ui.TabBadge
import local.clashfarmer.ui.components.Tone

/** The tab rail's width: the phone's bottom bar turned on its side for a wide window. */
private val RAIL_WIDTH = 88.dp

/**
 * The main window's body: the phone's FarmerDashboard laid out for a desktop window. A tab rail
 * on the left, the run strip across the top of every tab but Home while a run is active, and the
 * selected tab's page; calibration replaces all of it while open. Toasts, the account editor and
 * alerts show over it. Composing it starts the controller's once-a-second reads.
 */
@Composable
fun FarmerWindowContent(controller: FarmerController) {
    DisposableEffect(controller) {
        controller.windowShown()
        onDispose { controller.windowHidden() }
    }
    FarmerDesktopTheme {
        Box(Modifier.fillMaxSize().background(FarmerColors.background)) {
            val calibration = controller.calibration
            if (calibration != null) {
                CalibrationScreen(calibration)
            } else {
                Dashboard(controller.dashboard, controller)
            }
            // Centred under the page, right of the rail when it shows.
            ToastHost(controller, Modifier.align(Alignment.BottomCenter).padding(start = if (calibration == null) RAIL_WIDTH else 0.dp))
        }
        controller.editor?.let { editor ->
            AccountEditorSheet(editor, busy = controller.dashboard.state?.busy == true, onClose = controller::closeEditor)
        }
        controller.alerts.lastOrNull()?.let { alert ->
            MessageDialog(
                title = alert.title,
                message = alert.message,
                confirm = alert.confirm,
                onConfirm = {
                    controller.dismissAlert(alert)
                    alert.onConfirm()
                },
                onDismiss = { controller.dismissAlert(alert) },
                dismiss = alert.dismiss,
                monospace = alert.monospace
            )
        }
    }
}

@Composable
private fun Dashboard(dashboard: FarmerDashboard, host: DashboardHost) {
    Row(Modifier.fillMaxSize()) {
        TabRail(dashboard)
        Box(Modifier.width(1.dp).fillMaxHeight().background(FarmerColors.line))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val state = dashboard.state
            if (state != null && state.busy && dashboard.selectedTab != Tab.HOME.ordinal) {
                RunStrip(state, host)
                Divider()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Keyed, so one tab's remembered state and effects never carry over to the next.
                key(dashboard.selectedTab) {
                    dashboard.screens[dashboard.selectedTab].Content()
                }
            }
        }
    }
}

/** The five tabs, top to bottom, under the app icon. */
@Composable
private fun TabRail(dashboard: FarmerDashboard) {
    Column(
        modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight().background(FarmerColors.navBar).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Image(farmerIconPainter(), contentDescription = "Clash Farmer", modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.height(12.dp))
        Tab.entries.forEachIndexed { index, tab ->
            TabButton(tab, selected = dashboard.selectedTab == index, badge = dashboard.screens[index].badge) {
                dashboard.selectTab(index)
            }
        }
    }
}

/** One tab: icon over label, a gold pill behind the icon when selected, and an optional badge. */
@Composable
private fun TabButton(tab: Tab, selected: Boolean, badge: TabBadge?, onClick: () -> Unit) {
    val ink = if (selected) FarmerColors.gold else FarmerColors.muted
    Column(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .hoverClick(shape = RoundedCornerShape(16.dp), onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 28.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (selected) FarmerColors.withAlpha(FarmerColors.gold, 34) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            TabIcon(tab, ink, filled = selected, modifier = Modifier.size(22.dp))
            if (badge != null) TabBadgeView(badge, Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 8.dp))
        }
        Label(tab.title, Modifier.padding(top = 4.dp), size = FarmerTheme.LABEL, color = ink, bold = selected,
            medium = !selected, maxLines = 1, align = TextAlign.Center)
    }
}

/** A count in a pill (99+ past 99), or a plain 9 dp dot when the count is 0. */
@Composable
private fun TabBadgeView(badge: TabBadge, modifier: Modifier) {
    val dot = badge.count <= 0
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(if (dot) 9.dp else 16.dp)
            .defaultMinSize(minWidth = if (dot) 9.dp else 16.dp)
            .clip(shape)
            .background(badge.tone.ink)
            .border(1.dp, FarmerColors.navBar, shape)
            .padding(horizontal = if (dot) 0.dp else 4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!dot) {
            Label(if (badge.count > 99) "99+" else badge.count.toString(), size = 10f,
                color = if (badge.tone == Tone.WARN) FarmerColors.background else FarmerColors.text, bold = true, maxLines = 1)
        }
    }
}

/** Account, progress and phase of the run, with Pause or Resume and Stop. */
@Composable
private fun RunStrip(state: DashboardState, host: DashboardHost) {
    val snapshot = state.snapshot
    val progress = when {
        state.runContext.combinedRunId != null && state.runContext.combinedTotal > 0 ->
            "${state.runContext.combinedCompleted}/${state.runContext.combinedTotal}"
        snapshot.wallOnly -> "walls"
        snapshot.limit > 0 -> "${snapshot.completed}/${snapshot.limit}"
        else -> null
    }
    val phase = if (state.paused) "Paused" else snapshot.phase
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(FarmerColors.navBar)
            .padding(start = FarmerSize.GUTTER, top = 2.dp, end = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(if (state.paused) FarmerColors.warn else FarmerColors.ok, 8.dp)
        Spacer(Modifier.width(10.dp))
        Label(listOfNotNull(state.runAccountName, progress, phase).joinToString(" · "), Modifier.weight(1f),
            size = FarmerTheme.SMALL + 1, medium = true, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        SecondaryButton(if (state.paused) "Resume" else "Pause", compact = true) { host.act("pause") }
        Spacer(Modifier.width(6.dp))
        DangerButton("Stop", compact = true) { host.act("stop") }
    }
}

/** The phone's Toast: one message at a time near the foot of the window, gone after 2 or 3.5 s. */
@Composable
private fun ToastHost(controller: FarmerController, modifier: Modifier) {
    val toast = controller.currentToast ?: return
    LaunchedEffect(toast) {
        delay(if (toast.long) 3_500L else 2_000L)
        controller.toastDone(toast)
    }
    val shape = RoundedCornerShape(FarmerSize.BUTTON_RADIUS)
    Box(
        modifier = modifier
            .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
            .widthIn(max = 560.dp)
            .clip(shape)
            .background(FarmerColors.navBar)
            .border(1.dp, FarmerColors.line, shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Label(toast.text, size = FarmerTheme.SMALL + 1, align = TextAlign.Center)
    }
}
