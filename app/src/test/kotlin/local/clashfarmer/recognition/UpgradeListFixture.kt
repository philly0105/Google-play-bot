package local.clashfarmer.recognition

import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.UpgradeListFrame
import local.clashfarmer.core.Viewport

/**
 * The builder upgrade list of 29 September as recognised boxes, laid out from the captures in
 * audit-evidence/2026-09-29-upgrade-list-*.png (Sweat, 1600x720): popup x 649-985, y 100-555,
 * rows every 38.3 px, names left at x 662 (699 behind a small icon), prices right-aligned to
 * x 970 with the currency icon at x 850-872, gold above 6 838 668 drawn red.
 *
 * The wall row, its neighbours and the gold block are transcribed from 08-b-scroll3 and the head
 * of the list from 07. The dark elixir and elixir rows between "Other upgrades:" and the gold
 * block exist in the evidence only as a description, so their names and prices are stand-ins.
 */
object UpgradeListFixture {

    val screen = Viewport(0, 0, 1600, 720)

    const val GOLD = 6_838_668L
    const val ELIXIR = 4_444_799L

    sealed class Item(val y: Int)

    class Header(val text: String, y: Int) : Item(y)

    class Line(
        val name: String,
        y: Int,
        /** As drawn, or null for a row with no price. */
        val price: String? = null,
        /** "gold", "elixir", "dark", or null for an icon the pixels do not show. */
        val currency: String? = null,
        val payable: Boolean = true,
        val countdown: String? = null,
        val icon: Boolean = false,
        /** The reader ran name and price together as one box. */
        val merged: Boolean = false
    ) : Item(y)

    /** The bot's floating panel, which is in every frame of the evidence. */
    val overlay = listOf(
        RecognizedBox("Switching to Clash a...", 110, 38, 345, 66),
        RecognizedBox("10/40 attacks - 0 walls", 110, 68, 310, 92),
        RecognizedBox("Pause", 390, 50, 460, 80)
    )

    fun gold(name: String, y: Int, price: Long, icon: Boolean = false) =
        Line(name, y, spaced(price), "gold", price <= GOLD, icon = icon)

    fun elixir(name: String, y: Int, price: Long, icon: Boolean = false) =
        Line(name, y, spaced(price), "elixir", price <= ELIXIR, icon = icon)

    fun spaced(value: Long): String = value.toString().reversed().chunked(3).joinToString(" ").reversed()

    /** Sweat's whole list, top to bottom. [wallRow] is its Wall row's index in the result. */
    fun sweat(): List<Item> {
        var y = 123.0
        val items = mutableListOf<Item>()
        fun step() { y += 38.3 }
        fun at() = y.toInt()
        items += Header("Upgrades in progress:", at()); step()
        items += Line("Available!", at(), icon = true); step()
        items += Line("Gold Storage", at(), countdown = "5h 51m"); step()
        items += Line("Gold Storage", at(), countdown = "23h 46m"); step()
        items += Line("Wizard Tower", at(), countdown = "1d 11h"); step()
        items += Line("Dragon Duke", at(), countdown = "1d 23h"); step()
        items += Line("Ricochet Cannon", at(), countdown = "4d 23h"); step()
        items += Line("Available!", at(), icon = true); step()
        items += Header("Suggested upgrades:", at()); step()
        items += gold("Seeking Air Mine x8", at(), 6_500_000); step()
        items += elixir("Cake-A-Pult", at(), 4_000_000, icon = true); step()
        items += gold("Hero Hunter", at(), 3_500_000, icon = true); step()
        items += Header("Other upgrades:", at()); step()
        items += Line("Grand Warden", at(), "2 000 000", "dark", icon = true); step()
        items += Line("Royal Champion", at(), "1 800 000", "dark", icon = true); step()
        items += elixir("Laboratory x1", at(), 3_000_000); step()
        items += elixir("Barracks x2", at(), 2_500_000); step()
        items += gold("Giant Bomb x7", at(), 5_500_000); step()
        items += gold("Air Bomb x7", at(), 5_000_000); step()
        items += gold("Bomb x8", at(), 4_000_000); step()
        items += gold("Spring Trap x9", at(), 1_000_000); step()
        items += gold("Hot Candle", at(), 4_000_000, icon = true); step()
        items += gold("Hero Hunter", at(), 3_500_000, icon = true); step()
        items += gold("Cake-A-Pult", at(), 3_000_000, icon = true); step()
        items += gold("Wall x80", at(), 5_000_000); step()
        items += gold("Elixir Collector x7", at(), 8_000_000); step()
        items += gold("Builder's Hut x5", at(), 8_000_000); step()
        items += gold("Mortar x4", at(), 7_000_000); step()
        items += gold("Wizard Tower x4", at(), 8_000_000); step()
        items += gold("Hidden Tesla x5", at(), 8_200_000); step()
        items += gold("Bomb Tower x2", at(), 8_500_000); step()
        items += gold("Air Defense x4", at(), 9_000_000); step()
        items += gold("X-Bow x3", at(), 10_000_000); step()
        items += gold("Town Hall", at(), 16_000_000)
        return items
    }

    /**
     * Sweat's list of 8 October at 12:33 UTC, walls maxed for its Town Hall so no Wall row at all,
     * transcribed from audit-evidence/overnight/sweat-builder-list-1..4.png. Whether rows sit
     * between "Other upgrades:" on the first frame and Barbarian King on the second is not shown.
     * [wall] puts a Wall x80 at 5 000 000 gold in Spring Trap's place, four rows from the end.
     */
    fun sweatMaxed(wall: Boolean = false): List<Item> {
        var y = 123.0
        val items = mutableListOf<Item>()
        fun add(make: (Int) -> Item) { items += make(y.toInt()); y += 38.3 }
        fun dark(name: String, price: String) = { at: Int -> Line(name, at, price, "dark") as Item }
        add { Header("Upgrades in progress:", it) }
        add { Line("Available!", it, icon = true) }
        add { Line("Dragon Duke", it, countdown = "1d 16h") }
        add { Line("X-Bow", it, countdown = "5d 16h") }
        add { Line("Air Defense", it, countdown = "6d 4h") }
        add { Line("Archer Queen", it, countdown = "6d 16h") }
        add { Line("Workshop", it, countdown = "6d 16h") }
        add { Header("Suggested upgrades:", it) }
        add { gold("Elixir Collector x7", it, 8_000_000) }
        add { elixir("Grand Warden", it, 16_000_000) }
        add(dark("Royal Champion", "220 000"))
        add { Header("Other upgrades:", it) }
        add(dark("Barbarian King", "195 000"))
        add(dark("Minion Prince", "180 000"))
        add { Line("Hero Hunter", it, "60 000", "dark", icon = true) }
        add { Line("Cake-A-Pult", it, "35 000", "dark", icon = true) }
        add { Line("Hot Candle", it, "30 000", "dark", icon = true) }
        add { elixir("Spell Factory", it, 24_000_000) }
        add { elixir("Dark Spell Factory", it, 18_000_000) }
        add { elixir("Dark Elixir Drill x3", it, 12_000_000) }
        add { elixir("Dark Elixir Storage", it, 10_000_000) }
        add { elixir("Gold Mine x7", it, 8_000_000) }
        add { elixir("Cake-A-Pult", it, 4_000_000, icon = true) }
        add { elixir("Hot Candle", it, 3_500_000, icon = true) }
        add { elixir("Hero Hunter", it, 3_000_000, icon = true) }
        add { gold("Builder's Hut x5", it, 8_000_000) }
        add { gold("Air Defense x3", it, 9_000_000) }
        add { gold("Bomb Tower x2", it, 8_500_000) }
        add { gold("Hidden Tesla x5", it, 8_200_000) }
        add { gold("Wizard Tower x4", it, 8_000_000) }
        add { gold("Mortar x4", it, 7_000_000) }
        add { gold("Town Hall", it, 16_000_000) }
        add { gold("Seeking Air Mine x8", it, 6_500_000) }
        add { gold("Giant Bomb x7", it, 5_500_000) }
        add { gold("Air Bomb x7", it, 5_000_000) }
        add { gold("Bomb x7", it, 4_000_000) }
        add { if (wall) gold("Wall x80", it, 5_000_000) else gold("Spring Trap x9", it, 1_000_000) }
        add { gold("Hot Candle", it, 4_000_000, icon = true) }
        add { gold("Hero Hunter", it, 3_500_000, icon = true) }
        add { gold("Cake-A-Pult", it, 3_000_000, icon = true) }
        return items
    }

    private fun textWidth(text: String) = 9 * text.length

    /** The boxes of [items] when the list is scrolled down by [offset] pixels. */
    fun boxes(items: List<Item>, offset: Int, viewport: Viewport = screen): List<RecognizedBox> {
        val popup = upgradePopupRegion(viewport)
        val out = mutableListOf<RecognizedBox>()
        for (item in items) {
            val y = item.y - offset
            if (y < popup.top || y >= popup.bottom) continue
            when (item) {
                is Header -> out += RecognizedBox(item.text, 662, y - 10, 662 + textWidth(item.text), y + 10)
                is Line -> {
                    val left = if (item.icon) 699 else 662
                    if (item.merged && item.price != null) {
                        val text = "${item.name} ${item.price}"
                        out += RecognizedBox(text, left, y - 11, 970, y + 11)
                    } else {
                        out += RecognizedBox(item.name, left, y - 11, left + textWidth(item.name), y + 11)
                        item.price?.let { out += RecognizedBox(it, 866, y - 11, 970, y + 11) }
                    }
                    item.countdown?.let { out += RecognizedBox(it, 885, y - 11, 965, y + 11) }
                }
            }
        }
        return out
    }

    /** The row's centre in the list's own coordinates, for a test to say where it should be tapped. */
    fun lineY(items: List<Item>, name: String, price: String? = null): Int =
        items.filterIsInstance<Line>().first { it.name == name && (price == null || it.price == price) }.y

    /**
     * A capture of [items] at [offset]: the boxes, and pixels drawn the way the game draws them.
     * [state] is how the frame classifies, UNKNOWN for the list and HOME for the village under it.
     */
    class Frame(
        private val items: List<Item>,
        private val offset: Int,
        extra: List<RecognizedBox> = overlay,
        viewport: Viewport = screen,
        boxes: List<RecognizedBox> = boxes(items, offset, viewport) + extra,
        state: ScreenState = ScreenState.UNKNOWN
    ) : UpgradeListFrame {
        override val observation = Observation(state, boxes, boxes.joinToString(" ") { it.text }, viewport)

        private val white = 0xFFFFFFFF.toInt()
        private val red = 0xFFFF8C8C.toInt()
        private val gold = 0xFFFFD21C.toInt()
        private val elixir = 0xFFC832DC.toInt()
        private val dark = 0xFF2A2030.toInt()
        private val ground = 0xFF203020.toInt()

        override fun pixels(region: Region): IntArray {
            val out = IntArray(region.width * region.height) { ground }
            fun paint(from: Int, count: Int, colour: Int) {
                for (i in from until minOf(from + count, out.size)) out[i] = colour
            }
            for (line in items.filterIsInstance<Line>()) {
                val y = line.y - offset
                if (line.price == null || y < region.top - 11 || y > region.bottom + 11) continue
                val icon = when (line.currency) {
                    "gold" -> gold
                    "elixir" -> elixir
                    "dark" -> dark
                    else -> null
                }
                if (icon != null && overlap(region.left, region.right, 850, 872) >= 10) paint(0, 140, icon)
                if (overlap(region.left, region.right, 872, 970) >= 60) paint(140, 720, if (line.payable) white else red)
            }
            return out
        }

        private fun overlap(a0: Int, a1: Int, b0: Int, b1: Int) = minOf(a1, b1) - maxOf(a0, b0)
    }
}
