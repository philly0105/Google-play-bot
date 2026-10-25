package local.clashfarmer.desktop.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
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
import local.clashfarmer.core.Region
import local.clashfarmer.desktop.ui.components.DangerButton
import local.clashfarmer.desktop.ui.components.MessageDialog
import local.clashfarmer.desktop.ui.components.PrimaryButton
import local.clashfarmer.desktop.ui.components.SecondaryButton
import local.clashfarmer.desktop.ui.components.SectionHeader
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

/** The loupe's side, and how many frame pixels it shows across: each frame pixel is 4 dp in it. */
private val LOUPE_SIZE = 128.dp
private const val LOUPE_SPAN = 32

/**
 * Calibration in place of the tabs: the phone's CalibrationActivity laid out for a desktop
 * window, the frame on the left and the target list, actions and status in a sidebar.
 *
 * The keys ([calibrationKey]) are read on the whole screen, after any button that has focus: a
 * click on the frame takes focus back from the last button pressed, so Enter applies the point
 * rather than pressing that button again. Escape asks the way Back does.
 */
@Composable
fun CalibrationScreen(model: CalibrationModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(model) {
        // After the first frame, once the screen's nodes are attached.
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
    Row(
        Modifier
            .fillMaxSize()
            .background(FarmerColors.background)
            .onKeyEvent { e ->
                val action = calibrationKey(e.key, e.isShiftPressed) ?: return@onKeyEvent false
                if (e.type == KeyEventType.KeyDown) when (action) {
                    is CalibrationKey.Nudge -> model.nudge(action.dx, action.dy)
                    CalibrationKey.Apply -> model.applyPoint()
                    CalibrationKey.Back -> model.back()
                }
                true
            }
            .focusRequester(focus)
            .focusable()
    ) {
        FrameView(model, Modifier.weight(1f).fillMaxHeight()) { runCatching { focus.requestFocus() } }
        Box(Modifier.width(1.dp).fillMaxHeight().background(FarmerColors.line))
        Sidebar(model, Modifier.width(SIDEBAR_WIDTH).fillMaxHeight())
    }
    model.saveQuestion?.let { question ->
        MessageDialog(
            title = "Save at another shape?",
            message = question,
            confirm = "Save",
            onConfirm = model::confirmSave,
            onDismiss = model::cancelSave,
            dismiss = "Cancel"
        )
    }
    model.question?.let { question ->
        MessageDialog(
            title = question.title,
            message = question.message,
            confirm = question.confirm,
            onConfirm = model::confirmQuestion,
            onDismiss = model::cancelQuestion,
            dismiss = question.dismiss,
            // Leaving and retaking lose work; the standard positions change nothing until Save.
            danger = question != CalibrationModel.Question.STANDARD
        )
    }
}

/** What a key does on the calibration screen ([calibrationKey]). */
internal sealed interface CalibrationKey {
    /** Moves the point Apply would save by [dx], [dy] frame pixels. */
    class Nudge(val dx: Int, val dy: Int) : CalibrationKey

    /** Apply. */
    object Apply : CalibrationKey

    /** Back to dashboard, which asks first when points would be lost. */
    object Back : CalibrationKey
}

/**
 * The key's action, or null for a key the screen leaves alone. The arrows move the point 1 frame
 * pixel, 10 with Shift, as a click is rarely exact at the size the frame is shown; Enter applies
 * and Escape goes back.
 */
internal fun calibrationKey(key: Key, shift: Boolean): CalibrationKey? {
    val step = if (shift) 10 else 1
    return when (key) {
        Key.DirectionLeft -> CalibrationKey.Nudge(-step, 0)
        Key.DirectionRight -> CalibrationKey.Nudge(step, 0)
        Key.DirectionUp -> CalibrationKey.Nudge(0, -step)
        Key.DirectionDown -> CalibrationKey.Nudge(0, step)
        Key.Enter, Key.NumPadEnter -> CalibrationKey.Apply
        Key.Escape -> CalibrationKey.Back
        else -> null
    }
}

/**
 * The phone's CalibrationView: the frame as large as fits, centred, with the game area in green,
 * the selected target's saved point and box in green, the last card area in pink and a crosshair
 * on the last click, each with its numbers. A click on the frame picks the pixel under it and
 * calls [onFocus]. A loupe in a corner shows the pixels under the pointer, or around the last
 * click, four times their frame size. Marks are sized in dp, not the phone's pixels.
 */
@Composable
private fun FrameView(model: CalibrationModel, modifier: Modifier, onFocus: () -> Unit) {
    val image = model.frame
    Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        if (image == null) {
            // Nothing while the saved frame is read, so the empty note does not flash first.
            if (model.loaded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("No game screenshot yet.", size = FarmerTheme.TITLE, medium = true)
                    Label("Choose Capture game screen to begin.", Modifier.padding(top = 8.dp), color = FarmerColors.muted)
                }
            }
        } else {
            val measurer = rememberTextMeasurer()
            val textStyle = remember { farmerStyle(FarmerTheme.SMALL + 1, Color.White, medium = true).copy(shadow = TEXT_SHADOW) }
            var hover by remember(image) { mutableStateOf<Offset?>(null) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerHoverIcon(PointerIcon.Crosshair)
                    .pointerInput(model, image) {
                        detectTapGestures { tap ->
                            onFocus()
                            FrameFit(size.width.toFloat(), size.height.toFloat(), image.width, image.height)
                                .toImage(tap.x, tap.y)
                                ?.let { model.click(it) }
                        }
                    }
                    .pointerInput(image) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                hover = when (event.type) {
                                    PointerEventType.Exit -> null
                                    else -> event.changes.firstOrNull()?.position ?: hover
                                }
                            }
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

                fun box(region: Region, color: Color, width: Float) = drawRect(
                    color,
                    Offset(fit.x(region.left), fit.y(region.top)),
                    Size((region.right - region.left) * fit.scale, (region.bottom - region.top) * fit.scale),
                    style = Stroke(width)
                )

                val thin = 1.5.dp.toPx()
                val profile = model.profile

                // The game area
                val vp = profile.viewport
                box(Region(vp.left, vp.top, vp.right, vp.bottom), FarmerColors.ok, thin)
                label("Game area ${vp.width}x${vp.height}", fit.x(vp.left) + 5.dp.toPx(), fit.y(vp.top) + 15.dp.toPx())

                // Where the selected target is saved, when the frame is the profile's size, so it
                // is drawn where a run would click.
                if (image.width == profile.resolutionWidth && image.height == profile.resolutionHeight) {
                    savedBox(profile, model.target.name)?.let { box(it, FarmerColors.ok, thin) }
                    savedPoint(profile, model.target.name)?.let { pt ->
                        val spot = Offset(fit.x(pt.x), fit.y(pt.y))
                        drawCircle(FarmerColors.ok, radius = 7.dp.toPx(), center = spot, style = Stroke(2.dp.toPx()))
                        label("Saved", spot.x + 10.dp.toPx(), spot.y + 18.dp.toPx())
                    }
                }

                // The card area the last Apply saved
                model.selectedRegion?.let { reg ->
                    box(reg, FarmerColors.elixir, thin)
                    label("Card area", fit.x(reg.left) + 4.dp.toPx(), fit.y(reg.top) - 4.dp.toPx())
                }

                // The last click
                model.marker?.let { pt ->
                    val spot = Offset(fit.x(pt.x), fit.y(pt.y))
                    val thick = 2.dp.toPx()
                    val arm = 18.dp.toPx()
                    drawCircle(FarmerColors.bad, radius = 10.dp.toPx(), center = spot, style = Stroke(thick))
                    drawCircle(FarmerColors.gold, radius = 3.dp.toPx(), center = spot)
                    drawLine(FarmerColors.bad, Offset(spot.x - arm, spot.y), Offset(spot.x + arm, spot.y), thick)
                    drawLine(FarmerColors.bad, Offset(spot.x, spot.y - arm), Offset(spot.x, spot.y + arm), thick)
                    label("${pt.x}, ${pt.y}", spot.x + 12.dp.toPx(), spot.y - 12.dp.toPx())
                }

                val centre = hover?.let { fit.toImage(it.x, it.y) } ?: model.marker
                centre?.let { loupe(image, fit, it) }
            }
        }
    }
}

/**
 * The loupe: [LOUPE_SPAN] frame pixels around [centre], drawn [LOUPE_SIZE] wide without
 * smoothing so single pixels show, with a cross on [centre]. It sits in the frame's top-left
 * corner, or top-right when [centre] is under it.
 */
private fun DrawScope.loupe(image: ImageBitmap, fit: FrameFit, centre: Point) {
    val side = LOUPE_SIZE.toPx()
    val margin = 8.dp.toPx()
    val span = minOf(LOUPE_SPAN, image.width, image.height)
    val srcLeft = (centre.x - span / 2).coerceIn(0, image.width - span)
    val srcTop = (centre.y - span / 2).coerceIn(0, image.height - span)
    val onLeft = !(fit.x(centre.x) < fit.left + margin * 2 + side && fit.y(centre.y) < fit.top + margin * 2 + side)
    val x = if (onLeft) fit.left + margin else fit.left + image.width * fit.scale - margin - side
    val y = fit.top + margin
    drawRect(Color.Black, Offset(x - 1, y - 1), Size(side + 2, side + 2))
    drawImage(
        image = image,
        srcOffset = IntOffset(srcLeft, srcTop),
        srcSize = IntSize(span, span),
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(side.roundToInt(), side.roundToInt()),
        filterQuality = FilterQuality.None
    )
    val cell = side / span
    val cx = x + (centre.x - srcLeft + 0.5f) * cell
    val cy = y + (centre.y - srcTop + 0.5f) * cell
    val stroke = 1.dp.toPx()
    drawLine(FarmerColors.bad, Offset(x, cy), Offset(x + side, cy), stroke)
    drawLine(FarmerColors.bad, Offset(cx, y), Offset(cx, y + side), stroke)
    drawRect(FarmerColors.line, Offset(x, y), Size(side, side), style = Stroke(stroke))
}

/**
 * The phone's sidebar: the account and progress, the target picker, what to click for the
 * target and where it is, the actions, the profile's status, the actions that clear saved work,
 * and the way back. Every button but Back is off while a capture, a read or a save runs, and a
 * line under them says which.
 */
@Composable
private fun Sidebar(model: CalibrationModel, modifier: Modifier) {
    val scroll = rememberScrollState()
    val idle = !model.working
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(FarmerSize.GUTTER)) {
            Label(model.title, size = FarmerTheme.TITLE, bold = true, maxLines = 1)
            if (model.loaded) {
                Label(model.progressText, Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.gold)
            }
            Label(
                "Capture the game on the screen a point is on, click the point, then Apply. The arrow keys move it " +
                    "1 pixel, or 10 with Shift; Enter applies.",
                Modifier.padding(top = 6.dp, bottom = 12.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted
            )
            Label(model.target.group, Modifier.padding(bottom = 4.dp), size = FarmerTheme.LABEL, color = FarmerColors.muted)
            TargetPicker(model)
            Label(model.target.description, Modifier.padding(top = 8.dp, bottom = 6.dp), size = FarmerTheme.LABEL,
                color = FarmerColors.muted)
            Label(model.coordText, Modifier.padding(bottom = 12.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.gold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Capture game screen", Modifier.fillMaxWidth(), enabled = idle) { model.capture() }
                SecondaryButton("Apply point", Modifier.fillMaxWidth(), enabled = idle) { model.applyPoint() }
                SecondaryButton("Start from the standard positions", Modifier.fillMaxWidth(), enabled = model.standardEnabled) {
                    model.startFromStandard()
                }
                SecondaryButton("Show camera reference", Modifier.fillMaxWidth(), enabled = idle) { model.showCameraReference() }
                PrimaryButton("Save calibration", Modifier.fillMaxWidth(), enabled = idle) { model.save() }
            }
            model.task?.let { Label(it, Modifier.padding(top = 12.dp), size = FarmerTheme.SMALL, color = FarmerColors.gold) }
            if (model.loaded) {
                Label(model.statusText, Modifier.padding(top = 12.dp), size = FarmerTheme.LABEL, color = FarmerColors.muted)
            }
            SectionHeader("Start over", top = 16.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DangerButton("Clear collectors", Modifier.fillMaxWidth(), enabled = idle, compact = true) { model.clearCollectors() }
                DangerButton("Retake camera reference", Modifier.fillMaxWidth(), enabled = idle, compact = true) {
                    model.retakeCameraReference()
                }
            }
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Back to dashboard", Modifier.fillMaxWidth()) { model.back() }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp)
        )
    }
}

/**
 * The phone's Spinner of targets: the target shown, with a check when it is set, and the grouped
 * list under it on a click. Escape or a click outside closes the list.
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
            Check(isSet(model.profile, model.target.name) && model.target.name !in VIEWPORT_TARGETS)
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

/** A line of the target list ([targetRows]): a game screen's header, or the target at [index] in [CALIBRATION_TARGETS]. */
internal sealed interface TargetRow {
    class Header(val group: String) : TargetRow
    class Item(val index: Int) : TargetRow
}

/** [CALIBRATION_TARGETS] under a header for each game screen, in the list's order. */
internal fun targetRows(): List<TargetRow> = buildList {
    var group: String? = null
    CALIBRATION_TARGETS.forEachIndexed { index, target ->
        if (target.group != group) {
            group = target.group
            add(TargetRow.Header(target.group))
        }
        add(TargetRow.Item(index))
    }
}

/**
 * The 38 targets under their game screens, the one shown in gold. A set target has a check, one
 * an attack needs and lacks says "Needed", and the game area's corners, which Capture sets on
 * the PC, can't be picked. The list opens scrolled to the target shown.
 */
@Composable
private fun TargetList(model: CalibrationModel, modifier: Modifier, onPick: (Int) -> Unit) {
    val rows = remember { targetRows() }
    val first = rows.indexOfFirst { it is TargetRow.Item && it.index == model.targetIndex }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (first - 3).coerceAtLeast(0))
    val shape = RoundedCornerShape(12.dp)
    val profile = model.profile
    Box(
        modifier
            .heightIn(max = 420.dp)
            .clip(shape)
            .background(FarmerColors.navBar)
            .border(1.dp, FarmerColors.line, shape)
    ) {
        LazyColumn(state = listState, modifier = Modifier.padding(vertical = 4.dp)) {
            items(rows) { row ->
                when (row) {
                    is TargetRow.Header -> Label(
                        row.group,
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
                        size = FarmerTheme.LABEL,
                        color = FarmerColors.muted,
                        medium = true
                    )
                    is TargetRow.Item -> {
                        val target = CALIBRATION_TARGETS[row.index]
                        val selected = row.index == model.targetIndex
                        val enabled = targetEnabled(profile, target.name)
                        val set = isSet(profile, target.name)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hoverClick(enabled) { onPick(row.index) }
                                .background(if (selected) FarmerColors.withAlpha(FarmerColors.gold, 30) else Color.Transparent)
                                .padding(start = 14.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Check(set && enabled)
                            Label(
                                target.label,
                                Modifier.weight(1f),
                                size = FarmerTheme.SMALL + 1,
                                color = when {
                                    !enabled -> FarmerColors.muted
                                    selected -> FarmerColors.gold
                                    else -> FarmerColors.text
                                },
                                medium = selected,
                                maxLines = 1
                            )
                            val note = when {
                                !enabled -> "Set by Capture"
                                target.required && !set -> "Needed"
                                else -> null
                            }
                            note?.let {
                                Label(it, Modifier.padding(start = 6.dp), size = FarmerTheme.SMALL,
                                    color = if (enabled) FarmerColors.gold else FarmerColors.muted, maxLines = 1)
                            }
                        }
                    }
                }
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

/** A green check for a set target, or the same space left empty so the labels line up. */
@Composable
private fun Check(shown: Boolean) {
    Canvas(Modifier.padding(end = 8.dp).size(12.dp)) {
        if (!shown) return@Canvas
        val stroke = 2.dp.toPx()
        drawLine(FarmerColors.ok, Offset(0f, size.height * 0.55f), Offset(size.width * 0.38f, size.height * 0.9f), stroke)
        drawLine(FarmerColors.ok, Offset(size.width * 0.38f, size.height * 0.9f), Offset(size.width, size.height * 0.1f), stroke)
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
