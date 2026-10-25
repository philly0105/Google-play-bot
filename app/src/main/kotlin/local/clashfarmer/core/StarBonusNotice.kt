package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import java.util.Locale

/**
 * Clash's "Star Bonus Received!", which came up over Blood's village as the third supervised attack
 * of 24 October returned home with the day's fifth star (audit-evidence/live/20261024-171712-look).
 * The bonus is already paid, its loot to the Treasury and its ores to the Blacksmith, and the Okay
 * under it only closes the notice. Like the rating dialog it reads as the village, Battle and Build
 * legible around it, and its dim hid every balance from the checkpoint after that attack. On the
 * phone, Clash 18.647 paid the bonus through Claim Reward and a chest ([PopupGuard]); this result
 * had Return Home, and the phone tree has nothing for the notice.
 */
object StarBonusNotice {
    const val POPUP = "star_bonus_notice"

    /** The title and the body's "delivered to the Treasury", letters only. */
    private val WORDS = listOf("starbonusreceived", "deliveredtothetreasury")

    private val BUTTON = setOf("okay", "ok")

    /**
     * Okay's centre, or null when the frame is not the notice, Okay was not read below its words,
     * or anything on the frame sells.
     */
    fun okay(boxes: List<RecognizedBox>): Point? {
        val words = boxes.firstOrNull { box -> letters(box.text).let { text -> WORDS.any { text.contains(it) } } }
            ?: return null
        val text = boxes.joinToString(" ") { it.text }
        if (Policy.spendsPremium(text) || Policy.showsShop(text)) return null
        return boxes.firstOrNull {
            letters(it.text) in BUTTON && !it.text.any(Char::isDigit) && it.top > words.bottom
        }?.center
    }

    private fun letters(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
}
