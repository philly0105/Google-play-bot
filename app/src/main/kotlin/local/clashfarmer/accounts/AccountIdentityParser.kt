package local.clashfarmer.accounts

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.StopException
import local.clashfarmer.recognition.RecognizedBox
import java.util.Locale

data class SettingsIdentity(
    val villageName: String,
    val playerTag: String
)

sealed class IdentityVerificationResult {
    object Matched : IdentityVerificationResult()
    data class Mismatch(val reason: String) : IdentityVerificationResult()
    object Missing : IdentityVerificationResult()
}

data class PickerRow(
    val supercellName: String,
    val villageName: String,
    val clickPoint: Point,
    val rowBounds: Region
)

object AccountIdentityParser {

    /**
     * Normalizes text whitespace and case only.
     * Never substitutes ambiguous tag characters (e.g. O->0 or I->1).
     */
    fun normalize(text: String): String =
        text.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun normalizeTag(tag: String): String =
        normalize(tag).removePrefix("#")

    /**
     * The profile menu Settings moved into with Clash 18.647: tabs Clans, Social, Inbox and
     * Settings along its top. It opens on the profile tab, over a village whose Battle and Build
     * stay readable, so it is checked before the village. audit-evidence/clash-18647/31-profile-default.png.
     */
    fun isProfileMenu(rawText: String): Boolean {
        val normalized = normalize(rawText)
        return normalized.contains("INBOX") && normalized.contains("SOCIAL")
    }

    /** Strict text fallback for villages whose account-specific layout misses visual HOME anchors. */
    fun isVillageHome(rawText: String): Boolean {
        val normalized = normalize(rawText)
        // The button's own word, not "attacking" in a loading tip or "Attacks Completed!".
        // Since Clash 18.647 the buttons read Battle and Build; both are needed, since End Battle
        // and the menu's Ranked Battle carry the first.
        val hasAttack = Regex("(^|[^A-Z])ATTACK([^A-Z]|$)").containsMatchIn(normalized) ||
            (Regex("(^|[^A-Z])BAT[^A-Z ]?TLE([^A-Z]|$)").containsMatchIn(normalized) &&
                Regex("(^|[^A-Z])BUILD([^A-Z]|$)").containsMatchIn(normalized))
        val notInBattle = !normalized.contains("END BATTLE") &&
            !normalized.contains("LOADING") &&
            !normalized.contains("RETURN HOME") &&
            !normalized.contains("FIND A MATCH") &&
            !normalized.contains("MY ARMY")
        return hasAttack && notInBattle
    }

    /**
     * Parses the village name and player tag from Settings identity dialog OCR boxes.
     */
    fun parseSettingsIdentity(boxes: List<RecognizedBox>): SettingsIdentity? {
        val filtered = boxes.filter { it.text.isNotBlank() }.sortedBy { it.top }
        if (filtered.isEmpty()) return null

        // A village name is also alphanumeric. Without this digit/length rule, "Sweat" was
        // accepted as the tag and the real tag became the village name.
        val tagBoxIndex = filtered.indexOfFirst { box ->
            val t = box.text.trim()
            t.startsWith("#") ||
                (t.length in 8..15 && t.any(Char::isDigit) && t.matches(Regex("^[0-9A-Za-z]+$")))
        }

        if (tagBoxIndex != -1) {
            val rawTag = filtered[tagBoxIndex].text.trim()
            val playerTag = if (rawTag.startsWith("#")) rawTag else "#$rawTag"

            // Village name is typically the line above the tag
            val name = if (tagBoxIndex > 0) {
                filtered[tagBoxIndex - 1].text.trim()
            } else if (filtered.size > 1) {
                filtered[1].text.trim()
            } else {
                ""
            }

            if (name.isNotBlank()) {
                return SettingsIdentity(villageName = name, playerTag = playerTag)
            }
        }

        return null
    }

    /**
     * Verifies detected Settings identity against the expected AccountRecord.
     * Rejects unknown identity, missing identity, or mismatched player tag / village name.
     */
    fun verifySettingsIdentity(
        detected: SettingsIdentity?,
        expected: AccountRecord
    ): IdentityVerificationResult {
        if (detected == null) return IdentityVerificationResult.Missing

        val nameMatches = normalize(detected.villageName) == normalize(expected.villageName)
        val tagMatches = normalizeTag(detected.playerTag) == normalizeTag(expected.playerTag)

        return when {
            nameMatches && tagMatches -> IdentityVerificationResult.Matched
            nameMatches && !tagMatches -> IdentityVerificationResult.Mismatch(
                "Player tag mismatch for village '${expected.villageName}': expected '${expected.playerTag}', detected '${detected.playerTag}'"
            )
            !nameMatches && tagMatches -> IdentityVerificationResult.Mismatch(
                "Village name mismatch: expected '${expected.villageName}', detected '${detected.villageName}'"
            )
            else -> IdentityVerificationResult.Mismatch(
                "Identity mismatch: expected '${expected.villageName}' (${expected.playerTag}), detected '${detected.villageName}' (${detected.playerTag})"
            )
        }
    }

    /**
     * Parses account picker rows in the right half of the Supercell ID switcher screen (x: 758..1600).
     */
    fun parsePickerRows(boxes: List<RecognizedBox>): List<PickerRow> {
        // Exclude headers and other IDs / log in button
        val candidates = boxes.filter {
            it.center.x in 758..1600 && it.center.y in 100..550
        }.filterNot {
            val norm = normalize(it.text)
            norm.contains("SWITCH ID") || norm.contains("OTHER IDS") || norm.contains("LOG IN")
        }

        if (candidates.isEmpty()) return emptyList()

        // Cluster boxes into rows by y proximity (~70px)
        val sorted = candidates.sortedBy { it.top }
        val clusters = mutableListOf<MutableList<RecognizedBox>>()
        for (box in sorted) {
            val cluster = clusters.firstOrNull { c ->
                c.any { Math.abs(it.top - box.top) < 70 || Math.abs(it.bottom - box.bottom) < 70 }
            }
            if (cluster != null) {
                cluster.add(box)
            } else {
                clusters.add(mutableListOf(box))
            }
        }

        val rows = mutableListOf<PickerRow>()
        for (cluster in clusters) {
            val meaningful = cluster.filterNot {
                val t = normalize(it.text)
                t.contains("TOWN HALL") || t.matches(Regex("^\\d+$"))
            }.sortedBy { it.top }

            if (meaningful.isEmpty()) continue

            val supercellName: String
            val villageName: String

            if (meaningful.size == 1) {
                // If combined in single box/lines
                val lines = meaningful[0].text.lines().map { it.trim() }.filter { it.isNotBlank() }
                if (lines.size >= 2) {
                    supercellName = lines[0]
                    villageName = lines[1]
                } else {
                    supercellName = lines[0]
                    villageName = ""
                }
            } else {
                supercellName = meaningful[0].text.trim()
                villageName = meaningful[1].text.trim()
            }

            val minX = cluster.minOf { it.left }
            val minY = cluster.minOf { it.top }
            val maxX = cluster.maxOf { it.right }
            val maxY = cluster.maxOf { it.bottom }
            val center = Point((minX + maxX) / 2, (minY + maxY) / 2)

            rows.add(PickerRow(supercellName, villageName, center, Region(minX, minY, maxX, maxY)))
        }

        return rows
    }

    /**
     * Matches a target account row in the parsed picker rows by both SupercellName AND VillageName.
     * Does not rely on row position. Rejects missing or duplicate identities.
     */
    fun findPickerRow(
        rows: List<PickerRow>,
        target: AccountRecord
    ): PickerRow {
        val targetSc = normalize(target.supercellName)
        val targetVil = normalize(target.villageName)

        val matches = rows.filter { row ->
            val rowSc = normalize(row.supercellName)
            val rowVil = normalize(row.villageName)
            rowSc == targetSc && rowVil == targetVil
        }

        if (matches.isEmpty()) {
            throw StopException(
                "Account '${target.villageName}' ('${target.supercellName}') not found in Supercell ID picker. " +
                "Detected rows: ${rows.map { "'${it.villageName}' ('${it.supercellName}')" }}"
            )
        }

        if (matches.size > 1) {
            throw StopException(
                "Duplicate picker rows detected for account '${target.villageName}' ('${target.supercellName}')"
            )
        }

        return matches.first()
    }

    /**
     * Checks whether the screen text matches the exact benign "Welcome Back Chief!" dialog.
     */
    /**
     * The Layout Editor, which sits just above the gear. Its buttons read "Edit laYOUt" and
     * "CopY laYDUt", so the word is matched loosely but must come twice, or once beside the
     * editor's War Base tab or Set as active button.
     */
    fun isLayoutEditor(rawText: String): Boolean {
        val norm = normalize(rawText)
        val layoutWords = Regex("LA[YV][O0DU]{1,2}T").findAll(norm).count()
        return layoutWords >= 2 ||
            (layoutWords == 1 && (norm.contains("WAR BASE") || norm.contains("SET AS AC")))
    }

    fun isWelcomeBackDialog(rawText: String): Boolean {
        val norm = normalize(rawText)
        return norm.contains("WELCOME BACK CHIEF")
    }

    /**
     * Finds the Okay button inside the Welcome Back dialog if present.
     */
    fun findOkayButton(boxes: List<RecognizedBox>): Point? {
        val box = boxes.firstOrNull {
            val t = normalize(it.text)
            t == "OKAY" || t == "OK"
        }
        return box?.center
    }
}
