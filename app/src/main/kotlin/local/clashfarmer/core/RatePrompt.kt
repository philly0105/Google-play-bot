package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import java.util.Locale

/**
 * Google Play's "Rate Clash of Clans", which came up over Blood's village as a battle returned home
 * on 24 October (audit-evidence/live/20261024-163618-look). Like Clash's quit prompt it reads as the
 * village, Battle and Build legible around it. Like the idle dialog it is Android's: the title, the
 * body, then LATER on the left and NEVER and REVIEW on the right, all below the title. LATER is the
 * only button ever pressed: REVIEW leaves for the Play Store, and NEVER is the operator's to choose.
 */
object RatePrompt {
    const val POPUP = "rate_prompt"

    /** The title and the body's "rate or review", letters only. */
    private val WORDS = listOf("rateclashofclans", "rateorreview")

    /** LATER's centre, or null when the frame is not the dialog or LATER was not read below its words. */
    fun later(boxes: List<RecognizedBox>): Point? {
        val words = boxes.firstOrNull { box -> letters(box.text).let { text -> WORDS.any { text.contains(it) } } }
            ?: return null
        return boxes.firstOrNull {
            letters(it.text) == "later" && !it.text.any(Char::isDigit) && it.top > words.bottom
        }?.center
    }

    private fun letters(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
}
