package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import java.util.Locale

/** A collector or mine to tap, as it sits on the account's camera reference frame. */
data class CollectionTarget(val name: String, val point: Point)

/**
 * What one collection phase did. Taps only count as verified when a balance read before and
 * after the phase, each agreed by two frames, went up; anything unread counts for nothing.
 */
data class CollectionResult(val attempted: Int, val verified: Int, val skipped: Int, val reason: String?)

enum class CollectStep { TAP, SKIP, END }

/** How collection gets back to the bare village: Back, a tap on open ground, or nothing. */
enum class HomeStep { BACK, NEUTRAL, NONE }

data class CollectDecision(val step: CollectStep, val reason: String?)

/** When a collection target may be tapped. No Android here. */
object CollectionPolicy {

    const val MAX_TARGETS = 30
    const val MAX_PHASE_MS = 120_000L

    /**
     * The registration difference collection trusts. Good matches scored 5.2-15.4; a reference
     * whose village had since been rebuilt scored 28, which the wall phase's 40 still accepts.
     */
    const val MAX_CAMERA_DIFFERENCE = 20.0

    /**
     * The village, as fractions of the viewport, measured on the camera references of 24
     * September: inside the two button columns and below the top row, less the helper's own panel
     * with the name and builder icons under it (top left), the balances and the gem "+" (a shop
     * button, top right), and the corner buttons along the bottom. Clash 18.647 widened those:
     * the left buttons and event bar reach x 615, the chest, book and Build start at x 1195.
     */
    private const val LEFT = 0.156
    private const val RIGHT = 0.875
    private const val TOP = 0.1
    private const val BOTTOM = 0.97
    private const val OVERLAY_RIGHT = 0.44
    private const val OVERLAY_BOTTOM = 0.26
    private const val BALANCES_LEFT = 0.79
    private const val BALANCES_BOTTOM = 0.375
    private const val CORNERS_TOP = 0.83
    private const val CORNER_LEFT = 0.39
    private const val CORNER_RIGHT = 0.74

    /** The strip a selected building's name and buttons are drawn in, as the wall panel's. */
    private const val PANEL_TOP = 0.62
    private const val PANEL_BOTTOM = 0.88

    /**
     * Whether to tap [point], already carried onto the live camera. A stale camera, a pause since
     * the camera was matched (a person may have moved it), a screen that is not the village or a
     * panel left open ends the phase; a point off the village is only passed over.
     */
    fun decide(point: Point, viewport: Viewport, cameraMatched: Boolean, pausedSinceCamera: Boolean, atHome: Boolean, panelOpen: Boolean): CollectDecision = when {
        !cameraMatched -> CollectDecision(CollectStep.END, "camera_unmatched")
        pausedSinceCamera -> CollectDecision(CollectStep.END, "paused")
        !atHome -> CollectDecision(CollectStep.END, "not_home")
        panelOpen -> CollectDecision(CollectStep.END, "panel_open")
        !inVillage(point, viewport) -> CollectDecision(CollectStep.SKIP, "outside_village")
        else -> CollectDecision(CollectStep.TAP, null)
    }

    /**
     * What closes [state] on the way back to the village. A tap on open ground closes a panel and
     * nothing else; on the store or a menu it lands on whatever is drawn there, and on 15 October
     * a tap meant for the village went onto the store's $0.99. So the store, the attack menu and
     * the army get Back, and a screen nothing reads, a battle or a result gets no input at all.
     */
    fun homeStep(state: ScreenState, panel: Boolean, shop: Boolean): HomeStep = when {
        shop -> HomeStep.BACK
        panel || state == ScreenState.WALL -> HomeStep.NEUTRAL
        state == ScreenState.MENU || state == ScreenState.ARMY -> HomeStep.BACK
        else -> HomeStep.NONE
    }

    fun inVillage(point: Point, viewport: Viewport): Boolean {
        if (viewport.width <= 0 || viewport.height <= 0) return false
        val fx = (point.x - viewport.left).toDouble() / viewport.width
        val fy = (point.y - viewport.top).toDouble() / viewport.height
        if (fx !in LEFT..RIGHT || fy !in TOP..BOTTOM) return false
        if (fx < OVERLAY_RIGHT && fy < OVERLAY_BOTTOM) return false
        if (fx >= BALANCES_LEFT && fy < BALANCES_BOTTOM) return false
        return !(fy > CORNERS_TOP && (fx < CORNER_LEFT || fx > CORNER_RIGHT))
    }

    /** A selected building: Info, or a "(Level n)" title, in the strip its panel occupies. */
    fun buildingPanelOpen(boxes: List<RecognizedBox>, viewport: Viewport): Boolean {
        val top = viewport.top + (viewport.height * PANEL_TOP).toInt()
        val bottom = viewport.top + (viewport.height * PANEL_BOTTOM).toInt()
        return boxes.filter { it.top >= top && it.bottom <= bottom }.any { box ->
            val text = box.text.lowercase(Locale.ROOT)
            text.contains("info") || Regex("level\\s*\\d").containsMatchIn(text)
        }
    }

    /** The balances two frames read alike; one reading alone once showed nine million gold. */
    fun agreed(first: Map<Resource, Long>, second: Map<Resource, Long>): Map<Resource, Long> =
        first.filter { (resource, value) -> second[resource] == value }

    fun collected(before: Map<Resource, Long>, after: Map<Resource, Long>): Boolean =
        delta(before, after).values.any { it > 0L }

    fun cameraTrusted(difference: Double): Boolean = difference <= MAX_CAMERA_DIFFERENCE

    /**
     * The taps a phase's rise vouches for: every tap that did not select a building, when a
     * balance read before and after the whole phase went up; none otherwise. Which tap filled
     * which bar is not known.
     */
    fun verifiedTaps(unselected: Int, before: Map<Resource, Long>, after: Map<Resource, Long>): Int =
        if (collected(before, after)) unselected else 0

    /** The change in each resource read both times. It is not battle loot. */
    fun delta(before: Map<Resource, Long>, after: Map<Resource, Long>): Map<Resource, Long> =
        before.keys.intersect(after.keys).associateWith { after.getValue(it) - before.getValue(it) }
}
