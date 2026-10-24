package local.clashfarmer.desktop.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import local.clashfarmer.core.Point
import local.clashfarmer.desktop.ui.components.PrimaryButton
import local.clashfarmer.desktop.ui.components.SecondaryButton
import local.clashfarmer.desktop.ui.components.hoverClick
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.farmerStyle
import local.clashfarmer.ui.FarmerTheme
import kotlin.math.roundToInt

/** The sidebar's width; the frame takes the rest of the window. */
private val SIDEBAR_WIDTH = 340.dp

/** The phone's text shadow on the frame's labels, so they read on any part of the game. */
private val TEXT_SHADOW = Shadow(Color.Black, Offset(2f, 2f), 4f)

/**
 * Calibration in place of the tabs: the phone's CalibrationActivity laid out for a desktop
 * window, the frame on the left and the target list, actions and status in a sidebar.
 */
@Composable
fun CalibrationScreen(model: CalibrationModel) {
    Row(Modifier.fillMaxSize().background(FarmerColors.background)) {
        FrameView(model, Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(FarmerColors.line))
        Sidebar(model, Modifier.width(SIDEBAR_WIDTH).fillMaxHeight())
    }
}

/**
 * The phone's CalibrationView: the frame as large as fits, centred, with the calibrated viewport
 * in green, the last card area in pink and a crosshair on the last click, each with its numbers.
 * A click on the frame picks the pixel under it. Marks are sized in dp, not the phone's pixels.
 */
@Composable
private fun FrameView(model: CalibrationModel, modifier: Modifier) {
    val image = model.frame
    Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        if (image == null) {
            // Nothing while the saved frame is read, so the empty note does not flash first.
            if (model.loaded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("No game screenshot loaded.", size = FarmerTheme.TITLE, medium = true)
                    Label("Choose Capture game screen to begin.", Modifier.padding(top = 8.dp), color = FarmerColors.muted)
                }
            }
        } else {
            val measurer = rememberTextMeasurer()
            val textStyle = remember { farmerStyle(FarmerTheme.SMALL + 1, Color.White, medium = true).copy(shadow = TEXT_SHADOW) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerHoverIcon(PointerIcon.Crosshair)
                    .pointerInput(model, image) {
                        detectTapGestures { tap ->
                            FrameFit(size.width.toFloat(), size.height.toFloat(), image.width, image.height)
                                .toImage(tap.x, tap.y)
                                ?.let { model.click(it) }
                        }
                    }
            ) {
                val fit = FrameFit(size.width, size.height, image.width, image.height)
                drawImage(
                    image = image,
                    dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                    dstSize = IntSize((image.width * fit.scale).roundToInt(), (image.height * fit.scale).roundToInt()),
                    filterQuality = FilterQuality.Medium
                )

                /** White text with its baseline at [baseline], as Canvas.drawText placed it. */
                fun label(text: String, x: Float, baseline: Float) {
                    val layout = measurer.measure(text, textStyle)
                    drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
                }

                val thin = 1.5.dp.toPx()

                // Draw calibrated viewport
                val vp = model.profile.viewport
                drawRect(
                    FarmerColors.ok,
                    Offset(fit.x(vp.left), fit.y(vp.top)),
                    Size((vp.right - vp.left) * fit.scale, (vp.bottom - vp.top) * fit.scale),
                    style = Stroke(thin)
                )
                label("Viewport: ${vp.width}x${vp.height}", fit.x(vp.left) + 5.dp.toPx(), fit.y(vp.top) + 15.dp.toPx())

                // Draw selected region if set
                model.selectedRegion?.let { reg ->
                    drawRect(
                        FarmerColors.elixir,
                        Offset(fit.x(reg.left), fit.y(reg.top)),
                        Size((reg.right - reg.left) * fit.scale, (reg.bottom - reg.top) * fit.scale),
                        style = Stroke(thin)
                    )
                    label("ROI: [${reg.left}, ${reg.top}, ${reg.right}, ${reg.bottom}]",
                        fit.x(reg.left) + 4.dp.toPx(), fit.y(reg.top) - 4.dp.toPx())
                }

                // Draw selected point marker
                model.marker?.let { pt ->
                    val spot = Offset(fit.x(pt.x), fit.y(pt.y))
                    val thick = 2.dp.toPx()
                    val arm = 18.dp.toPx()
                    drawCircle(FarmerColors.bad, radius = 10.dp.toPx(), center = spot, style = Stroke(thick))
                    drawCircle(FarmerColors.gold, radius = 3.dp.toPx(), center = spot)
                    drawLine(FarmerColors.bad, Offset(spot.x - arm, spot.y), Offset(spot.x + arm, spot.y), thick)
                    drawLine(FarmerColors.bad, Offset(spot.x, spot.y - arm), Offset(spot.x, spot.y + arm), thick)
                    label("(${pt.x}, ${pt.y})", spot.x + 12.dp.toPx(), spot.y - 12.dp.toPx())
                }
            }
        }
    }
}

/**
 * The phone's sidebar: the target list, what to click for the target, the clicked point, the
 * actions, the profile's status and the way back. Every button but Back is off while a capture,
 * a read or a save runs, and a line under them says which.
 */
@Composable
private fun Sidebar(model: CalibrationModel, modifier: Modifier) {
    val scroll = rememberScrollState()
    val idle = !model.working
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(FarmerSize.GUTTER)) {
            Label("Device calibration", Modifier.padding(bottom = 10.dp), size = FarmerTheme.TITLE, color = FarmerColors.gold)
            TargetPicker(model)
            Label(model.target.description, Modifier.padding(top = 8.dp, bottom = 10.dp), size = FarmerTheme.LABEL,
                color = FarmerColors.muted)
            Label(model.coordText, Modifier.padding(bottom = 12.dp), size = FarmerTheme.SMALL, color = FarmerColors.gold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Capture game screen", Modifier.fillMaxWidth(), enabled = idle) { model.capture() }
                SecondaryButton("Apply selected point", Modifier.fillMaxWidth(), enabled = idle) { model.applyPoint() }
                SecondaryButton("Show camera reference", Modifier.fillMaxWidth(), enabled = idle) { model.showCameraReference() }
                SecondaryButton("Clear collectors", Modifier.fillMaxWidth(), enabled = idle) { model.clearCollectors() }
                SecondaryButton("Retake camera reference", Modifier.fillMaxWidth(), enabled = idle) { model.retakeCameraReference() }
                PrimaryButton("Save calibration", Modifier.fillMaxWidth(), enabled = idle) { model.save() }
            }
            model.task?.let { Label(it, Modifier.padding(top = 12.dp), size = FarmerTheme.SMALL, color = FarmerColors.gold) }
            if (model.loaded) {
                Label(model.statusText, Modifier.padding(vertical = 12.dp), size = FarmerTheme.LABEL, color = FarmerColors.muted)
            } else {
                Spacer(Modifier.height(12.dp))
            }
            SecondaryButton("Back to dashboard", Modifier.fillMaxWidth()) { model.back() }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp)
        )
    }
}

/**
 * The phone's Spinner of targets: the target shown, and the list of all 38 under it on a click.
 * Escape or a click outside closes the list.
 */
@Composable
private fun TargetPicker(model: CalibrationModel) {
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    val position = remember(gap) { BelowAnchor(gap) }
    val shape = RoundedCornerShape(FarmerSize.BUTTON_RADIUS)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorWidth = it.width }
                .defaultMinSize(minHeight = FarmerSize.CONTROL)
                .clip(shape)
                .background(FarmerColors.surfaceStrong)
                .border(1.dp, FarmerColors.line, shape)
                .hoverClick(shape = shape) { open = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label(model.target.label, Modifier.weight(1f), size = FarmerTheme.SMALL + 1, medium = true, maxLines = 1)
            Chevron(Modifier.padding(start = 8.dp).size(width = 10.dp, height = 6.dp))
        }
        if (open) {
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true)
            ) {
                TargetList(model, Modifier.width(with(density) { anchorWidth.toDp() })) { index ->
                    model.pickTarget(index)
                    open = false
                }
            }
        }
    }
}

/** The 38 targets, the one shown in gold. The list opens scrolled to it. */
@Composable
private fun TargetList(model: CalibrationModel, modifier: Modifier, onPick: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (model.targetIndex - 3).coerceAtLeast(0))
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(max = 360.dp)
            .clip(shape)
            .background(FarmerColors.navBar)
            .border(1.dp, FarmerColors.line, shape)
    ) {
        LazyColumn(state = listState, modifier = Modifier.padding(vertical = 4.dp)) {
            itemsIndexed(CALIBRATION_TARGETS) { index, target ->
                val selected = index == model.targetIndex
                Label(
                    target.label,
                    Modifier
                        .fillMaxWidth()
                        .hoverClick { onPick(index) }
                        .background(if (selected) FarmerColors.withAlpha(FarmerColors.gold, 30) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    size = FarmerTheme.SMALL + 1,
                    color = if (selected) FarmerColors.gold else FarmerColors.text,
                    medium = selected,
                    maxLines = 1
                )
            }
        }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp)
            )
        }
    }
}

/** The Spinner's arrow: a small down chevron. */
@Composable
private fun Chevron(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        drawLine(FarmerColors.muted, Offset(0f, 0f), Offset(size.width / 2f, size.height), stroke)
        drawLine(FarmerColors.muted, Offset(size.width / 2f, size.height), Offset(size.width, 0f), stroke)
    }
}

/** Opens the list just under its button, or above it when the window has no room below. */
private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        }
        return IntOffset(anchorBounds.left, y)
    }
}

/**
 * The phone's CalibrationView fit: the frame as large as the view allows, centred, never
 * cropped. [toImage] turns a point in the view into a frame pixel, or null off the frame.
 */
internal class FrameFit(viewWidth: Float, viewHeight: Float, private val imageWidth: Int, private val imageHeight: Int) {
    val scale: Float = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val left: Float = (viewWidth - imageWidth * scale) / 2f
    val top: Float = (viewHeight - imageHeight * scale) / 2f

    /** A frame x as a view x. */
    fun x(px: Int): Float = left + px * scale

    /** A frame y as a view y. */
    fun y(py: Int): Float = top + py * scale

    fun toImage(tx: Float, ty: Float): Point? {
        if (tx < left || tx > left + imageWidth * scale || ty < top || ty > top + imageHeight * scale) return null
        val px = ((tx - left) / scale).toInt().coerceIn(0, imageWidth - 1)
        val py = ((ty - top) / scale).toInt().coerceIn(0, imageHeight - 1)
        return Point(px, py)
    }
}
