package local.clashfarmer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import local.clashfarmer.core.Resource
import local.clashfarmer.desktop.ui.components.Card
import local.clashfarmer.desktop.ui.components.Divider
import local.clashfarmer.desktop.ui.components.Gap
import local.clashfarmer.desktop.ui.components.SectionHeader
import local.clashfarmer.desktop.ui.components.Sheet
import local.clashfarmer.desktop.ui.components.hoverClick
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.insight.EventText
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.SessionStats
import local.clashfarmer.ui.FarmerTheme
import java.time.ZoneId

/**
 * One session's raids (spec 2.4): its title and battle-time rate, then a table with one row per
 * battle, oldest first, "-" where the result was not read. Clicking a row opens that battle over
 * it; closing that returns here. Show it by composing it; [onClose] must stop composing it.
 */
@Composable
fun RaidsSheet(session: SessionStats.SessionRow, zone: ZoneId, onClose: () -> Unit) {
    var open by remember { mutableStateOf<Pair<SessionStats.RaidRow, BattleRecord>?>(null) }
    val rows = remember(session, zone) { SessionStats.raidRows(session, zone).zip(session.raids) }
    Sheet("Raids", onClose) {
        // The title counts completed battles; the table below lists the rest too, so say how many.
        val unfinished = session.raids.size - session.battles - session.interrupted
        val title = listOfNotNull(
            SessionStats.sessionTitle(session, zone),
            session.interrupted.takeIf { it > 0 }?.let { "$it interrupted" },
            unfinished.takeIf { it > 0 }?.let { "$it not finished" }
        ).joinToString(" · ")
        Label(title, Modifier.padding(top = 8.dp), size = FarmerTheme.BODY, bold = true)
        Label(SessionStats.battleTimeLine(session), Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL,
            color = FarmerColors.muted)
        Gap(16.dp)

        Card(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Cells(SessionStats.RAID_HEADERS, header = true, Modifier.defaultMinSize(minHeight = 36.dp))
                rows.forEach { (raid, battle) ->
                    Divider()
                    RaidLine(raid, battle) { open = raid to battle }
                }
            }
        }
        Label("Click a row for that battle's details.", size = FarmerTheme.LABEL, color = FarmerColors.faint)
    }
    open?.let { (raid, battle) -> BattleSheet(session, raid, battle, zone) { open = null } }
}

@Composable
private fun RaidLine(raid: SessionStats.RaidRow, battle: BattleRecord, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FarmerSize.CONTROL)
            .hoverClick(shape = RoundedCornerShape(8.dp), onClick = onClick)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Cells(listOf(raid.time, raid.gold, raid.elixir, raid.dark, raid.stars, raid.damage, raid.skips), header = false)
        // Worded as the title and the battle's detail say it; SessionStats.raidRow still says "in progress".
        val note = if (battle.outcome == BattleRecord.IN_PROGRESS) "not finished" else raid.note
        if (note.isNotEmpty()) {
            Label(note, Modifier.padding(top = 2.dp), size = FarmerTheme.LABEL, color = FarmerColors.warn)
        }
    }
}

/** Seven equal columns: the time left-aligned, the numbers right-aligned in tabular digits. */
@Composable
private fun Cells(values: List<String>, header: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { i, value ->
            Label(
                value,
                Modifier.weight(1f),
                size = if (header) FarmerTheme.LABEL else FarmerTheme.SMALL,
                color = when {
                    header -> FarmerColors.muted
                    value == Format.UNKNOWN -> FarmerColors.faint
                    else -> FarmerColors.text
                },
                medium = header,
                maxLines = 1,
                align = if (i == 0) TextAlign.Start else TextAlign.End,
                tabular = true
            )
        }
    }
}

/**
 * One battle's facts, each value selectable. Its ids sit apart under Details: they mean nothing
 * to read, and are there to copy into a bug report.
 */
@Composable
private fun BattleSheet(
    session: SessionStats.SessionRow,
    raid: SessionStats.RaidRow,
    b: BattleRecord,
    zone: ZoneId,
    onClose: () -> Unit
) {
    val facts = listOf(
        "Account" to session.accountName,
        "Started" to Format.dayTime(b.startedAtMs, zone),
        "Ended" to Format.dayTime(b.endedAtMs, zone),
        "Outcome" to outcome(b),
        "Earned" to loot(b.earnedLoot),
        "Offered by the scout" to loot(b.availableLoot),
        "Stars" to raid.stars,
        "Damage" to raid.damage,
        "Bases skipped" to raid.skips,
        "Gold spent searching" to Format.amount(b.searchGold)
    )
    Sheet("Battle at ${raid.time}", onClose) {
        Facts(facts, Modifier.padding(top = 8.dp))
        SectionHeader("Details")
        Label("For a bug report: select an id to copy it.", Modifier.padding(bottom = 8.dp), size = FarmerTheme.SMALL,
            color = FarmerColors.muted)
        Facts(listOf("Battle id" to b.id, "Session id" to b.sessionId), small = true)
    }
}

/** A card of name and value lines, the values selectable; [small] values are faint monospace. */
@Composable
private fun Facts(facts: List<Pair<String, String>>, modifier: Modifier = Modifier, small: Boolean = false) {
    Card(modifier) {
        SelectionContainer {
            Column {
                facts.forEachIndexed { i, (name, value) ->
                    if (i > 0) Divider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        DisableSelection {
                            Label(name, size = FarmerTheme.LABEL, color = FarmerColors.muted, medium = true)
                        }
                        if (small) {
                            Label(value, Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted,
                                monospace = true)
                        } else {
                            Label(value, Modifier.padding(top = 4.dp), size = FarmerTheme.BODY)
                        }
                    }
                }
            }
        }
    }
}

private fun outcome(b: BattleRecord): String = when (b.outcome) {
    BattleRecord.COMPLETED -> "Completed"
    BattleRecord.INTERRUPTED -> "Interrupted: " + EventText.stopLine(b.reason)
    // Left open by a killed process until the next farm run closes it, so not "in progress".
    else -> "Not finished"
}

/** "Gold 412k · Elixir 398k · Dark elixir 2.1k"; nothing read at all is "not read". */
private fun loot(values: Map<Resource, Long>): String =
    if (values.isEmpty()) "not read"
    else Resource.entries.joinToString(" · ") { "${Format.resourceName(it)} ${Format.amount(values[it])}" }
