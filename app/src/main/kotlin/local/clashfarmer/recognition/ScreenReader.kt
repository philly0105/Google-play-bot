package local.clashfarmer.recognition

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import local.clashfarmer.core.Builders
import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.Policy
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import local.clashfarmer.log.AppLog
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenReader : AutoCloseable {

    companion object {
        /**
         * An amount ROI holds nothing but a number, so every letter in it is a misread digit.
         * Clash's font gives its zero a notch the recogniser keeps returning as "D" and its
         * four an open top it returns as "A": "4 000 000" came back as "A0DD 0DD", and the
         * wall phase priced that wall at 400.
         */
        fun normalizeAmountText(text: String): String = text
            .replace('O', '0')
            .replace('o', '0')
            .replace('D', '0')
            .replace('A', '4')
            .replace('B', '8')
            .replace('s', '5')
            .replace('S', '5')
            .replace('I', '1')
            .replace('l', '1')
            .replace('i', '1')
            .replace('Z', '2')
            .replace('G', '6')
            .replace('T', '7')
            .replace(Regex("[()\\[\\]{}|]"), "")
            .trim()
            .replace(COIN_RIM, "")

        /**
         * Clash groups every balance in threes, so a last group of four digits, or a lone digit
         * after a group of three, is never the number. When that extra digit is a 1 it is the
         * dark rim of the coin at the bar's right edge: Blood's gold settled at 8 540 626 on 30
         * September and read as 85 406 261, so a two-wall purchase that had gone through stopped
         * the run as unproven. The same bar read "16 670 8571" on 28 September.
         */
        private val COIN_RIM = Regex("(?<=\\s\\d{3})\\s?1$")

        /**
         * The 18.647 font gives its one a flag at the top, and a leading one now comes back as
         * "T": 1 588 988 elixir left in Sweat's battle read "T588 988" from four masks on 14
         * October, and Blood's 180 940 dark elixir "TB0 940". [normalizeAmountText] makes every
         * T a 7, the expensive guess for a balance or a loot row; no leading T in those frames
         * was a 7. Wall prices keep the 7, where the higher price is the cautious one.
         */
        fun leadingOne(text: String): String = text.replace(LEADING_T, "1")

        private val LEADING_T = Regex("^\\s*T")

        /**
         * Whether a normalised reading has the number's shape: every group after the first is a
         * whole number of threes. A cut that splits one glyph in two leaves a group of four, and
         * its digits outvote a shorter truth on length: on 15 October Blood's 5 000 000 elixir
         * read "5 000 000" from six masks and "5 O00D O00" from two, the eight digits won, and a
         * real three-wall purchase stopped a two-hour run as unproven. A group of six stays, since
         * the recogniser often drops one space: "12 474588" was Sweat's 12 474 588 gold, read
         * that way by twelve probe cuts of 14 October. A reading with no space passes; it carries
         * no evidence either way. Nor does a split inside the first group show: the probe's crop
         * from x 1355 read the same elixir as "S000O 000", and that one still votes.
         */
        fun numberShaped(text: String): Boolean = text.trim().split(Regex("\\s+"))
            .map { it.replace(Regex("[^0-9]"), "") }
            .filter { it.isNotEmpty() }
            .drop(1)
            .all { it.length % 3 == 0 }

        /**
         * Whether every mask of one frame read [balance]: none read other digits with the
         * number's shape, and at most one read nothing or lacks the shape. [raw] is the list
         * [readBalance] fills, "label:text" for each mask that read digits. On 15 October Blood's
         * elixir read 387 427 through all nine masks at 04:12, and 3 876 306 through eight at
         * 05:01 with the 240 cut's "908 9L8 E" beside them; both were held against a stored
         * 29 000 000 that was no longer true. [HomeBalanceGuard] lets such a balance beat the prior.
         */
        fun everyMaskRead(raw: List<String>, balance: Long): Boolean {
            val shaped = raw.map { it.substringAfter(':') }.filter(::numberShaped).map { it.replace(Regex("[^0-9]"), "") }
            return shaped.all { it == balance.toString() } &&
                shaped.size >= BALANCE_CUTS.size + BALANCE_WHITE_CUTS.size * BALANCE_GAPS.size - 1
        }

        /**
         * The masks a village balance is read through. Clash 18.647 draws the storage's fill under
         * the digits, gold at about 211 luminance, and the old cuts from 160 to 205 inked it too:
         * Blood's 5 172 115 gold read as 517 215 on 14 October. Luminance cuts above the fill,
         * and white masks with the glyphs pulled apart ([ImageMatcher.whiteSpacedCrop]), read all
         * twelve bars of four village frames right, the true value at least five times in nine;
         * the old eight cuts managed it three times in eight on the worst bar.
         */
        private val BALANCE_CUTS = listOf(215, 230, 240)
        private val BALANCE_WHITE_CUTS = listOf(150, 180, 210)
        private val BALANCE_GAPS = listOf(2, 3)

        /**
         * Settles a set of readings of the same number taken at different binarisation cuts.
         *
         * Both ways this can go wrong have been seen on this phone. A cut that is too harsh drops
         * a whole group of digits - "6 116 661" came back as "616 661" - and a short reading can
         * agree with itself, so the longest reading wins. A cut that is too soft glues a scrap of
         * the icon beside the bar onto the front - "1 034 762" came back as "44 034 762" - and
         * that invents a longer reading, so a length only counts if two cuts independently
         * produced it. Six agreeing reads of the real balance were being thrown away by one
         * eight-digit smear before this rule told the two cases apart.
         */
        /**
         * The most digits a gold or elixir balance can have.
         *
         * Storages cap these in the tens of millions, so a nine-digit reading is never a
         * balance - it is a smear. That mattered on 17 September: 16 605 162 elixir came back
         * from one cut as 166 055 162, the doubled-glyph rule below preferred it to the eight
         * digit reading the other cuts agreed on, and the wall phase then bought a wall against
         * a balance ten times the real one. The purchase was affordable anyway and the settle
         * check caught the discrepancy and stopped the run, which is the only reason this cost
         * nothing. Readings this long are dropped before any vote is taken.
         */
        private const val MAX_BALANCE_DIGITS = 8

        private val SCOUT_CUTS = listOf(215, 200, 230, 185)
        private val DAMAGE_CUTS = listOf(140, 130, 150, 125)

        /**
         * The tray's white "xNN" badges. The event card's pale background measured 220 on Blood's
         * scout of 7 October, so every cut sits above it; 232 to 252 all gave clean glyphs.
         */
        private val BADGE_CUTS = listOf(235, 240, 245, 250)

        /**
         * The PC's. Google Play Games draws the badge's white at 220 to 229, under every phone cut.
         * On Blood's 1740x978 scout of 24 October, over card art under 135, every cut from 145 to
         * 215 read the x44, x5 and x1 but 190 (the x5 as "KS") and 200 (no x1); 220 lost the x1
         * and 225 read nothing. No event card has been measured on the PC.
         */
        private val PC_BADGE_CUTS = listOf(175, 185, 205, 210)

        fun agreedAmount(allDigitStrings: List<String>): Long? =
            agreedDigits(allDigitStrings)?.toLongOrNull()?.takeIf { it > 0 }

        /**
         * A village balance, which unlike a price can really be zero: Sweat's elixir stood at 0
         * on 23 September. The same agreement rule, and nothing restored from a lone reading.
         */
        fun agreedBalance(allDigitStrings: List<String>): Long? =
            agreedDigits(allDigitStrings)?.toLongOrNull()

        /**
         * Whether one reading is the agreed value with a collapsed pair of ones put back.
         *
         * That reading may well be the truth - see [collapsedDouble] - but one cut is not
         * evidence enough to multiply a balance by ten. Another frame's readings, pooled with
         * these, settle it: if the long form turns up again it wins the vote outright.
         */
        fun needsAnotherFrame(allDigitStrings: List<String>): Boolean {
            val agreed = agreedDigits(allDigitStrings) ?: return false
            return allDigitStrings.any { collapsedDouble(it, agreed) }
        }

        private fun agreedDigits(allDigitStrings: List<String>): String? {
            val digitStrings = allDigitStrings.filter { it.length <= MAX_BALANCE_DIGITS }
            // Longest first, but a length only settles the matter if two cuts produced the same
            // string at it. Taking the longest length with two readings of *any* kind and
            // stopping there threw away a balance three cuts agreed on: two nine-digit smears,
            // "157 608 651" and "915 608 651", outranked "15 608 651" read three times, and a
            // phase holding fifteen million stopped dead rather than buy a wall.
            val agreed = digitStrings
                .groupBy { it.length }
                .toSortedMap(reverseOrder())
                .asSequence()
                .mapNotNull { (_, sameLength) ->
                    sameLength.groupingBy { it }.eachCount()
                        .filterValues { it >= 2 }
                        .maxByOrNull { it.value }
                        ?.key
                }
                .firstOrNull()
            return agreed
        }

        /**
         * Whether `longer` is `agreed` with one doubled digit put back.
         *
         * A balance of 11 831 452 came back as 1 831 452 from every cut but one: two adjacent
         * ones in this font are two thin strokes a pixel apart, and most thresholds glue them
         * into a single glyph. The consensus is then wrong by a factor of ten, which declined a
         * 4 000 000 wall the account could easily afford.
         *
         * It used to be put back on the strength of that one reading. Now it only asks for a
         * second frame; see [needsAnotherFrame].
         *
         * A single odd reading is not normally allowed to overrule the consensus - that rule is
         * there because a soft cut can smear a scrap of the currency icon onto the front of the
         * number, which is how "1 034 762" once read as "44 034 762". The difference is what the
         * extra character is: a collapsed double is one character sitting next to its own twin,
         * and deleting it gives the consensus back exactly. "44 034 762" is not that - no single
         * deletion turns it into "1 034 762" - so it stays rejected.
         *
         * Only a pair of ones collapses. Any other doubled digit is one cut splitting a glyph in
         * two, and believing it multiplies the balance by ten: on 22 September 4 013 597 was
         * settled as 40 135 597 and 5 821 143 as 55 821 143. A round balance is worse, because
         * the currency icon at the end of the bar reads as one more zero beside the last one.
         */
        private fun collapsedDouble(longer: String, agreed: String): Boolean {
            if (longer.length != agreed.length + 1) return false
            for (i in longer.indices) {
                if (longer.removeRange(i, i + 1) != agreed) continue
                val c = longer[i]
                if (c != '1') continue
                if (c == longer.getOrNull(i - 1) || c == longer.getOrNull(i + 1)) return true
            }
            return false
        }
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun observe(
        bitmap: Bitmap,
        customViewport: Viewport? = null,
        acquisitionTimeMs: Long = System.currentTimeMillis()
    ): Observation {
        val viewport = customViewport ?: Viewport(0, 0, bitmap.width, bitmap.height)
        val needCrop = viewport.left != 0 || viewport.top != 0 || viewport.width != bitmap.width || viewport.height != bitmap.height

        val (targetBitmap, offsetX, offsetY, mustRecycle) = if (needCrop) {
            val clampedWidth = viewport.width.coerceIn(1, bitmap.width - viewport.left)
            val clampedHeight = viewport.height.coerceIn(1, bitmap.height - viewport.top)
            val cropped = Bitmap.createBitmap(bitmap, viewport.left, viewport.top, clampedWidth, clampedHeight)
            Quadruple(cropped, viewport.left, viewport.top, true)
        } else {
            Quadruple(bitmap, 0, 0, false)
        }

        try {
            val inputImage = InputImage.fromBitmap(targetBitmap, 0)
            val visionText: Text = recognizer.process(inputImage).awaitTask()

            val boxes = mutableListOf<RecognizedBox>()
            val allText = StringBuilder()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val rect = line.boundingBox
                    val box = RecognizedBox(
                        text = line.text,
                        left = (rect?.left ?: 0) + offsetX,
                        top = (rect?.top ?: 0) + offsetY,
                        right = (rect?.right ?: 0) + offsetX,
                        bottom = (rect?.bottom ?: 0) + offsetY
                    )
                    boxes.add(box)
                    allText.append(line.text).append(" ")
                }
            }

            val rawText = allText.toString()
            val state = classifyScreen(boxes, viewport)
            val read = if (state == ScreenState.HOME) withBuildersCorner(bitmap, boxes, viewport) else boxes

            return Observation(
                state = state,
                boxes = read,
                rawText = rawText,
                viewport = viewport,
                timestampMs = acquisitionTimeMs
            )
        } finally {
            if (mustRecycle) {
                targetBitmap.recycle()
            }
        }
    }

    /**
     * A PC village frame's boxes with the builders' counter read again from an upscaled crop when
     * the whole frame missed it, so the wall phase knows whether a builder is free. The crop's
     * boxes stand in for the corner's only when they read a count; otherwise [boxes] come back as
     * they were. The phone, and a frame that already read the count, take no second read.
     */
    private suspend fun withBuildersCorner(bitmap: Bitmap, boxes: List<RecognizedBox>, viewport: Viewport): List<RecognizedBox> {
        val region = Builders.pcCornerRegion(viewport) ?: return boxes
        if (Builders.read(boxes, viewport) != null) return boxes
        val crop = observeRegion(bitmap, region, Builders.PC_CORNER_SCALE).boxes
        val merged = Builders.withCorner(boxes, crop, viewport)
        return if (Builders.read(merged, viewport) != null) merged else boxes
    }

    /**
     * Reads a cropped region with optional upscaling (e.g. scale=2) for smaller text like player tags.
     * Maps all recognized bounding boxes back to the original bitmap coordinates.
     */
    suspend fun observeRegion(
        bitmap: Bitmap,
        region: Region,
        scale: Int = 2,
        acquisitionTimeMs: Long = System.currentTimeMillis()
    ): Observation {
        val clampedLeft = region.left.coerceIn(0, bitmap.width - 1)
        val clampedTop = region.top.coerceIn(0, bitmap.height - 1)
        val clampedWidth = (region.right - clampedLeft).coerceIn(1, bitmap.width - clampedLeft)
        val clampedHeight = (region.bottom - clampedTop).coerceIn(1, bitmap.height - clampedTop)

        val cropped = Bitmap.createBitmap(bitmap, clampedLeft, clampedTop, clampedWidth, clampedHeight)
        val scaled = if (scale > 1) {
            val s = Bitmap.createScaledBitmap(cropped, clampedWidth * scale, clampedHeight * scale, true)
            cropped.recycle()
            s
        } else {
            cropped
        }

        try {
            val inputImage = InputImage.fromBitmap(scaled, 0)
            val visionText: Text = recognizer.process(inputImage).awaitTask()

            val boxes = mutableListOf<RecognizedBox>()
            val allText = StringBuilder()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val rect = line.boundingBox
                    val box = RecognizedBox(
                        text = line.text,
                        left = clampedLeft + (rect?.left ?: 0) / scale,
                        top = clampedTop + (rect?.top ?: 0) / scale,
                        right = clampedLeft + (rect?.right ?: 0) / scale,
                        bottom = clampedTop + (rect?.bottom ?: 0) / scale
                    )
                    boxes.add(box)
                    allText.append(line.text).append(" ")
                }
            }

            val rawText = allText.toString()
            val state = Policy.classify(rawText)

            return Observation(
                state = state,
                boxes = boxes,
                rawText = rawText,
                viewport = Viewport(clampedLeft, clampedTop, clampedLeft + clampedWidth, clampedTop + clampedHeight),
                timestampMs = acquisitionTimeMs
            )
        } finally {
            scaled.recycle()
        }
    }

    /**
     * A strictly positive amount - a price, or a balance being spent from - agreed between cuts.
     *
     * When the only disagreement is a lone reading with a collapsed pair of ones put back,
     * [nextFrame] supplies one more frame, which this recycles, and its readings are pooled.
     */
    suspend fun readAmountWithAgreement(
        bitmap: Bitmap,
        roi: Region,
        nextFrame: (suspend () -> Bitmap)? = null
    ): Long {
        val rawReadings = mutableListOf<String>()
        val every = mutableListOf<String>()
        val digitStrings = amountDigits(bitmap, roi, rawReadings, allowZero = false, every)
        if (nextFrame != null && (needsAnotherFrame(digitStrings) || needsAnotherFrame(every))) {
            AppLog.log("amount_second_frame", "roi" to roi.toString(), "readings" to digitStrings.joinToString(","))
            val next = nextFrame()
            try {
                digitStrings += amountDigits(next, roi, rawReadings, allowZero = false)
            } finally { next.recycle() }
        }

        agreedAmount(digitStrings)?.let { return it }

        AppLog.log("amount_unreadable", "roi" to roi.toString(), "raw" to rawReadings.joinToString(" | "))
        throw StopException("Resource amount in ROI $roi was not read the same way twice (readings: $digitStrings)")
    }

    /**
     * A village balance for goal checks: zero allowed, null when the cuts do not agree. [raw],
     * when given, receives what each cut read, a second frame's cuts included.
     */
    suspend fun readBalanceWithAgreement(
        bitmap: Bitmap,
        roi: Region,
        raw: MutableList<String>? = null,
        nextFrame: suspend () -> Bitmap
    ): Long? {
        val rawReadings = raw ?: mutableListOf()
        val every = mutableListOf<String>()
        val digits = amountDigits(bitmap, roi, rawReadings, allowZero = true, every)
        if (needsAnotherFrame(digits) || needsAnotherFrame(every)) {
            val next = nextFrame()
            try {
                digits += amountDigits(next, roi, rawReadings, allowZero = true)
            } finally {
                next.recycle()
            }
        }
        return agreedBalance(digits)
    }

    /** A single HOME frame. [raw], when given, receives what each cut read. */
    suspend fun readBalance(bitmap: Bitmap, roi: Region, raw: MutableList<String>? = null): Long? {
        val rawReadings = mutableListOf<String>()
        val balance = agreedBalance(amountDigits(bitmap, roi, rawReadings, allowZero = true))
        raw?.addAll(rawReadings)
        if (balance == null) {
            AppLog.log("balance_unreadable", "roi" to roi.toString(), "raw" to rawReadings.joinToString(" | "))
        }
        return balance
    }

    /**
     * The digits each cut read, without the readings that lack the number's shape ([numberShaped]).
     * [every], when given, receives those too. They do not vote, but they still show a collapsed
     * pair of ones. In nine of the probe's cuts of Blood's 5 172 115 gold on 14 October, six read
     * it a one short ("S 172 1S") and one read it whole, and only the six show that the whole
     * reading is a pair put back and not a smear.
     */
    private suspend fun amountDigits(
        bitmap: Bitmap,
        roi: Region,
        rawReadings: MutableList<String>,
        allowZero: Boolean,
        every: MutableList<String>? = null
    ): MutableList<String> {
        // These digits are about 13px tall on this phone's resource bars, which is where
        // "6 116 661" came back as "616 661" - a whole group lost. More pixels to work with.
        val masks: List<Pair<String, () -> Bitmap>> =
            BALANCE_CUTS.map { cut -> "$cut" to { ImageMatcher.binarizeCrop(bitmap, roi, threshold = cut, scale = 5) } } +
                BALANCE_WHITE_CUTS.flatMap { cut ->
                    BALANCE_GAPS.map { gap -> "w${cut}g$gap" to { ImageMatcher.whiteSpacedCrop(bitmap, roi, cut, gap, scale = 5) } }
                }
        val digitStrings = mutableListOf<String>()

        for ((label, crop) in masks) {
            val mask = crop()
            try {
                val inputImage = InputImage.fromBitmap(mask, 0)
                val textResult = recognizer.process(inputImage).awaitTask()
                val lines = textResult.textBlocks.flatMap { it.lines }
                if (lines.isEmpty()) continue

                val raw = normalizeAmountText(leadingOne(lines.joinToString(" ") { it.text }))

                val digits = raw.replace(Regex("[^0-9]"), "")
                if (digits.isNotEmpty()) {
                    rawReadings.add("$label:$raw")
                    // A price is never legitimately zero. "4 000 000" reduced to a single
                    // surviving group reads as "000", which agreed with itself and priced a
                    // wall at nothing. A balance can be zero, but only as one digit.
                    val num = digits.toLongOrNull() ?: continue
                    if (num == 0L && (!allowZero || digits.length != 1)) continue
                    every?.add(digits)
                    if (numberShaped(raw)) digitStrings.add(digits)
                }
            } finally {
                mask.recycle()
            }
        }
        return digitStrings
    }

    /**
     * One row of the scout's loot panel; null when the cuts do not agree. See [LootPanelReader].
     * The battle shows the same rows as loot left; those misses log as [unreadEvent]
     * "battle_loot_unreadable" so they do not pass for scouting misreads.
     */
    suspend fun readScoutLoot(bitmap: Bitmap, roi: Region, unreadEvent: String = "scout_loot_unreadable"): Long? {
        val raw = cutReadings(bitmap, roi, SCOUT_CUTS, scale = 4)
        val loot = LootPanelReader.lootAmount(raw)
        if (loot == null) AppLog.log(unreadEvent, "roi" to roi.toString(), "raw" to raw.joinToString(" | "))
        return loot
    }

    suspend fun readScoutCountdown(bitmap: Bitmap, roi: Region): Int? {
        val raw = cutReadings(bitmap, roi, SCOUT_CUTS, scale = 2)
        val seconds = LootPanelReader.countdownSeconds(raw)
        if (seconds == null) AppLog.log("scout_countdown_unreadable", "raw" to raw.joinToString(" | "))
        return seconds
    }

    suspend fun readNextFee(bitmap: Bitmap, roi: Region): Long? {
        val raw = cutReadings(bitmap, roi, SCOUT_CUTS, scale = 3)
        val fee = LootPanelReader.nextFee(raw)
        if (fee == null) AppLog.log("scout_fee_unreadable", "raw" to raw.joinToString(" | "))
        return fee
    }

    /** What each cut of a tray card's badge band reads as; parsed by AttackPlanner.badgeCount. */
    suspend fun readTrayBadge(bitmap: Bitmap, roi: Region): List<String> =
        cutReadings(bitmap, roi, PcGeometry.pick(Viewport(0, 0, bitmap.width, bitmap.height), BADGE_CUTS, PC_BADGE_CUTS), scale = 4)

    /**
     * One "You got" row of the result screen; null when the cuts do not agree, or when what they
     * agree on has fewer or more digits than the row's ink has glyphs.
     */
    suspend fun readResultLoot(bitmap: Bitmap, roi: Region): Long? {
        val raw = cutReadings(bitmap, roi, SCOUT_CUTS, scale = 3)
        val read = LootPanelReader.lootAmount(raw)
        val glyphs = BattleResultReader.glyphCount(roi, Viewport(0, 0, bitmap.width, bitmap.height)) { x, y ->
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y) else null
        }
        val loot = read?.takeIf { BattleResultReader.inkAgrees(it, glyphs) }
        if (loot == null) AppLog.log("result_loot_unreadable", "roi" to roi.toString(), "raw" to raw.joinToString(" | "),
            "read" to read, "glyphs" to glyphs)
        return loot
    }

    /**
     * The result's total damage, read first from masks of its green digits
     * ([BattleResultReader.DAMAGE_INKS]) and then, if those do not agree, with them pooled with
     * luminance cuts. Those cuts sit between the digits' luminance, up to 168, and the dark sky
     * under 121 of 21 and 22 September; on an earned middle star the star is brighter than the
     * digits and the cuts read "" or "1", which is what the green masks are for.
     */
    suspend fun readResultDamage(bitmap: Bitmap, roi: Region): Int? {
        val green = greenReadings(bitmap, roi)
        BattleResultReader.damagePercent(green)?.let { return it }
        val raw = green + cutReadings(bitmap, roi, DAMAGE_CUTS, scale = 3)
        val damage = BattleResultReader.damagePercent(raw)
        if (damage == null) AppLog.log("result_damage_unreadable", "raw" to raw.joinToString(" | "))
        return damage
    }

    /** Each green mask of [roi] at two scales, as the text it reads. */
    private suspend fun greenReadings(bitmap: Bitmap, roi: Region): List<String> {
        val left = roi.left.coerceIn(0, bitmap.width - 1)
        val top = roi.top.coerceIn(0, bitmap.height - 1)
        val w = (roi.right - left).coerceIn(1, bitmap.width - left)
        val h = (roi.bottom - top).coerceIn(1, bitmap.height - top)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        val readings = mutableListOf<String>()
        for (ink in BattleResultReader.DAMAGE_INKS) {
            val mask = Bitmap.createBitmap(
                IntArray(pixels.size) { if (ink.matches(pixels[it])) Color.BLACK else Color.WHITE },
                w, h, Bitmap.Config.ARGB_8888
            )
            try {
                for (scale in listOf(3, 4)) {
                    val scaled = Bitmap.createScaledBitmap(mask, w * scale, h * scale, false)
                    try {
                        recognizer.process(InputImage.fromBitmap(scaled, 0)).awaitTask().text.trim()
                            .takeIf { it.isNotEmpty() }?.let(readings::add)
                    } finally { scaled.recycle() }
                }
            } finally { mask.recycle() }
        }
        return readings
    }

    /** The result's earned stars, sampled at [centres] ([BattleResultReader.starCentres]). */
    fun readResultStars(bitmap: Bitmap, centres: List<Point>): Int? =
        BattleResultReader.starCount(centres) { x, y ->
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y) else null
        }

    /**
     * The text each cut of [roi] reads as. The scout's white and pale-gold digits sit on a dark
     * translucent panel and stay whole from 185 to 230 in the frames of 24 September.
     */
    private suspend fun cutReadings(bitmap: Bitmap, roi: Region, cuts: List<Int>, scale: Int): List<String> =
        cuts.mapNotNull { cut ->
            val mask = ImageMatcher.binarizeCrop(bitmap, roi, threshold = cut, scale = scale)
            try {
                recognizer.process(InputImage.fromBitmap(mask, 0)).awaitTask().text.trim().takeIf { it.isNotEmpty() }
            } finally { mask.recycle() }
        }

    /** Yellow outlined dialog titles need their shadow separated from the digits. */
    suspend fun readWallDialogLevel(bitmap: Bitmap, roi: Region): Int? {
        val readings = cutReadings(bitmap, roi, listOf(160, 180, 200), scale = 3)
        AppLog.log("wall_dialog_level_reads", "text" to readings.joinToString(" | "))
        return readings.mapNotNull { local.clashfarmer.core.WallPanel.targetLevel(it) }
            .groupingBy { it }.eachCount().filterValues { it >= 2 }.keys.singleOrNull()
    }

    /**
     * A yellow price on a green button, or null. Blood's gold confirm on 30 September carried a
     * Gold Pass discount, 8 000 000 in yellow over a struck-through 10 000 000, and the plain read
     * returned "o0000", so a phase that had priced and planned the wall refused at the dialog.
     * Two of the mask settings must give the same number.
     */
    suspend fun readYellowPrice(bitmap: Bitmap, roi: Region): Long? {
        val readings = mutableListOf<Long>()
        val raw = mutableListOf<String>()
        for (blueCut in listOf(60, 80, 100)) {
            for (scale in listOf(3, 4)) {
                val mask = ImageMatcher.yellowCrop(bitmap, roi, blueCut, scale)
                try {
                    val text = recognizer.process(InputImage.fromBitmap(mask, 0)).awaitTask().text.trim()
                    raw += text
                    local.clashfarmer.core.WallPanel.embeddedPrice(text)?.let(readings::add)
                } finally { mask.recycle() }
            }
        }
        AppLog.log("wall_dialog_yellow_price", "readings" to readings.joinToString(","), "raw" to raw.joinToString(" | "))
        return readings.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys.singleOrNull()
    }

    suspend fun readSmallPriceWithAgreement(bitmap: Bitmap, roi: Region): Long {
        val readings = mutableListOf<Long>()
        for (scale in listOf(4, 5, 6)) {
            for (cut in listOf(130, 135, 140, 145, 150, 155, 160)) {
                val mask = ImageMatcher.binarizeCrop(bitmap, roi, threshold = cut, scale = scale)
                try {
                    val text = recognizer.process(InputImage.fromBitmap(mask, 0)).awaitTask().text
                    local.clashfarmer.core.WallPanel.smallPriceCandidate(text)?.let(readings::add)
                } finally { mask.recycle() }
            }
        }
        return readings.groupingBy { it }.eachCount().filterValues { it >= 2 }
            .maxByOrNull { it.value }?.key
            ?: throw StopException("Wall unit price in ROI $roi was not read consistently (readings: $readings)")
    }

    suspend fun readWallSelection(bitmap: Bitmap, roi: Region): Pair<Int, Boolean> {
        val thresholds = listOf(235, 240, 245, 230, 195, 205, 215, 225)
        val readings = mutableListOf<Pair<Int, Boolean>>()

        for (cut in thresholds) {
            val mask = ImageMatcher.binarizeCrop(bitmap, roi, threshold = cut, scale = 3)
            try {
                val inputImage = InputImage.fromBitmap(mask, 0)
                val textResult = recognizer.process(inputImage).awaitTask()
                val text = textResult.text.lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9 ]"), " ")
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")

                val isGroup = text.contains(" x")
                val head = text.replace(Regex(" x\\b.*$"), "")
                val numbers = Regex("\\d+").findAll(head).map { it.value.toInt() }.toList()

                if (head.startsWith("wall") && numbers.size == 1) {
                    val level = numbers[0]
                    val reading = Pair(level, isGroup)
                    readings.add(reading)
                    if (readings.count { it == reading } >= 2) {
                        return reading
                    }
                }
            } finally {
                mask.recycle()
            }
        }

        throw StopException("Selected wall level in ROI $roi is ambiguous or not recognized (readings: $readings)")
    }

    override fun close() {
        recognizer.close()
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exception ->
            if (cont.isActive) cont.resumeWithException(exception)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
