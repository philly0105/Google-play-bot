package local.clashfarmer.desktop.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.desktop.ui.CsvExport
import local.clashfarmer.desktop.ui.DashboardHost
import local.clashfarmer.desktop.ui.DashboardScreen
import local.clashfarmer.desktop.ui.RaidsSheet
import local.clashfarmer.desktop.ui.components.BarChart
import local.clashfarmer.desktop.ui.components.Card
import local.clashfarmer.desktop.ui.components.ChartBar
import local.clashfarmer.desktop.ui.components.ChartGroup
import local.clashfarmer.desktop.ui.components.Chip
import local.clashfarmer.desktop.ui.components.Divider
import local.clashfarmer.desktop.ui.components.Dot
import local.clashfarmer.desktop.ui.components.Gap
import local.clashfarmer.desktop.ui.components.HBarChart
import local.clashfarmer.desktop.ui.components.HBarRow
import local.clashfarmer.desktop.ui.components.ScreenPage
import local.clashfarmer.desktop.ui.components.SecondaryButton
import local.clashfarmer.desktop.ui.components.SectionHeader
import local.clashfarmer.desktop.ui.components.Segmented
import local.clashfarmer.desktop.ui.components.StatTileRow
import local.clashfarmer.desktop.ui.components.Tile
import local.clashfarmer.desktop.ui.components.hoverClick
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.history.BattleHistoryStore
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.HistoryCsv
import local.clashfarmer.insight.SessionStats
import local.clashfarmer.log.RunLog
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.AccountColor
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.StatsModel
import java.io.File
import java.time.ZoneId

/**
 * Stats (spec 2.4): is it earning. Tiles for the period, loot per hour by session and by account,
 * the session list with its raids sheet, and CSV export. History is read on IO, never in [bind].
 */
class StatsScreen(private val host: DashboardHost) : DashboardScreen {
    /** Read each time, so Today and the row times follow a zone change as the remote page does. */
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val historyDir = File(host.filesDir, "history/battles")
    private val runLog = RunLog.shared(File(host.filesDir, "logs"))
    private val scroll = ScrollState(0)

    private data class Loaded(val records: List<BattleRecord>, val corrupt: Int)

    private var loaded: Loaded? = null
    private var loading = false
    /** A load was asked for while one ran; it is asked again when that one ends. */
    private var again = false
    private var deferred: Job? = null
    private var loadedAt = 0L
    private var exporting = false
    private var accounts: List<AccountRecord> by mutableStateOf(emptyList())
    private var period by mutableStateOf(SessionStats.Period.DAYS_7)
    private var accountId: String? by mutableStateOf(null)
    private var shownSessions by mutableStateOf(PAGE)
    private var model: StatsModel? by mutableStateOf(null)
    /** The end words [model] was built with. */
    private var endings: Map<String, String> = emptyMap()
    /** The line under the title: loading, the history's coverage and files left out, or a read error. */
    private var status: String? by mutableStateOf("Loading raids...")
    /** The session whose raids sheet is open. */
    private var raids: SessionStats.SessionRow? by mutableStateOf(null)
    /** Whether the page is on screen, the phone's view.isShown. */
    private var visible = false

    override fun bind(state: DashboardState) {
        if (state.accounts != accounts) {
            accounts = state.accounts
            // A filter on an account that left the picker falls back to All.
            val ids = accounts.take(MAX_FILTER_ACCOUNTS).map { it.id }
            if (accountId != null && accountId !in ids) accountId = null
            if (loaded != null) render()
        }
        // While the tab is open, a running session's battles keep arriving; load() reads at most every 30 s.
        if (loaded != null && visible) load()
    }

    override fun onShown() {
        // The bind just before may have started a load; with nothing loaded yet this always loads.
        if (!loading) load()
    }

    @Composable
    override fun Content() {
        DisposableEffect(Unit) {
            visible = true
            onDispose { visible = false }
        }
        ScreenPage(scroll = scroll) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("Stats", Modifier.weight(1f), size = FarmerTheme.HEADLINE, bold = true)
                SecondaryButton("Export CSV", compact = true) { export() }
            }
            status?.let {
                Label(it, Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
            }
            Segmented(SessionStats.Period.entries.map { it.label }, period.ordinal, Modifier.padding(top = 12.dp)) { i ->
                period = SessionStats.Period.entries[i]
                shownSessions = PAGE
                render()
            }
            AccountFilter()
            Gap(16.dp)
            model?.let { Body(it) }
        }
        raids?.let { row -> key(row.sessionId) { RaidsSheet(row, zone) { raids = null } } }
    }

    /** "All" plus up to three accounts, the most a Segmented row holds. */
    @Composable
    private fun AccountFilter() {
        val shown = accounts.take(MAX_FILTER_ACCOUNTS)
        if (shown.isEmpty()) return
        val ids = shown.map { it.id }
        val selected = accountId?.let { ids.indexOf(it) + 1 } ?: 0
        Segmented(listOf("All") + shown.map { it.displayName }, selected, Modifier.padding(top = 8.dp)) { i ->
            accountId = if (i == 0) null else ids.getOrNull(i - 1)
            shownSessions = PAGE
            render()
        }
    }

    /** The empty card, or the tiles, the charts and the session list. */
    @Composable
    private fun Body(model: StatsModel) {
        val emptyLine = model.emptyLine
        if (emptyLine != null) {
            Card {
                Label(emptyLine, size = FarmerTheme.TITLE, bold = true)
                Label(model.emptyDetail, Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
            }
            return
        }
        StatTileRow(listOf(
            Tile(model.rateText, "loot per hour"),
            Tile(model.battlesText, "battles"),
            Tile(model.totalText, "loot total")
        ))
        model.unreadChip?.let { Chip(it, Tone.WARN, Modifier.padding(bottom = 12.dp), dot = true) }

        Card {
            SectionHeader("Loot per hour by session", action = model.maxText)
            BarChart(
                model.bars.map { ChartGroup(it.label, listOf(ChartBar(it.lootPerHour.toDouble(), Color(AccountColor.of(it.accountId))))) },
                Modifier.height(200.dp),
                // No axis number over a flat chart: its scale is a placeholder.
                format = { if (model.maxText == null) "" else Format.amount(it.toLong()) },
                emptyMessage = model.chartEmpty,
                onBarClick = { i -> model.bars.getOrNull(i)?.let { bar -> openSession(bar.sessionId) } }
            )
            Label("Click a bar to see that session's raids.", Modifier.padding(top = 6.dp), size = FarmerTheme.LABEL,
                color = FarmerColors.faint)
        }

        // Empty while the period's only battle is still under way: no blank box under the header.
        if (model.byAccount.isNotEmpty()) {
            Card {
                SectionHeader("By account (${model.period.label})")
                HBarChart(model.byAccount.map {
                    HBarRow(it.name, it.lootPerHour?.toDouble(), Format.rate(it.lootPerHour), Color(AccountColor.of(it.accountId)),
                        Format.hours(it.farmingMs))
                })
            }
        }

        SectionHeader("Sessions")
        val rows = model.sessions
        Card(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                rows.take(shownSessions).forEachIndexed { i, row ->
                    if (i > 0) Divider()
                    SessionLine(row)
                }
            }
        }
        if (rows.size > shownSessions) {
            SecondaryButton("Show more", Modifier.fillMaxWidth()) { shownSessions += PAGE }
        }
    }

    @Composable
    private fun SessionLine(row: SessionStats.SessionRow) {
        val text = StatsModel.line(row, zone)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .hoverClick(shape = RoundedCornerShape(8.dp)) { raids = row }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dot(Color(AccountColor.of(row.accountId)), 8.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Label(text.title, size = FarmerTheme.BODY, bold = true, maxLines = 1)
                Label(text.detail, Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted, maxLines = 2)
            }
            Label(text.rate, Modifier.padding(start = 8.dp), size = FarmerTheme.BODY,
                color = if (row.lootPerHour == null) FarmerColors.faint else FarmerColors.text, bold = true)
            Label(">", Modifier.padding(start = 10.dp), size = FarmerTheme.BODY, color = FarmerColors.faint)
        }
    }

    private fun load() {
        if (loading) {
            again = true
            return
        }
        val wait = CACHE_MS - (System.currentTimeMillis() - loadedAt)
        if (loaded != null && wait > 0) {
            // Put off, never dropped: binds stop once a run ends, and the last one can land inside the 30 s.
            if (deferred?.isActive != true) deferred = host.scope.launch {
                delay(wait)
                deferred = null
                if (visible) load()
            }
            return
        }
        deferred?.cancel()
        deferred = null
        loading = true
        again = false
        host.scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { BattleHistoryStore(historyDir).load().let { Loaded(it.records, it.corrupt.size) } }
            }
            // runCatching also catches cancellation; a closed window's screens must not be touched.
            if (!isActive) return@launch
            loadedAt = System.currentTimeMillis()
            result.onSuccess { next ->
                loaded = next
                // Even with nothing new: the period moves with the clock (Today after midnight), and
                // render() clears an earlier read error from the status line.
                render()
            }.onFailure {
                warn("History read failed", it)
                status = "History could not be read."
            }
            // The numbers are up, with the end words the last scan found; this scan fills in the rest.
            // A log that will not read costs only the end words, never the numbers.
            if (result.isSuccess) {
                val words = runCatching { withContext(Dispatchers.IO) { runLog.endings() } }.getOrNull()
                if (!isActive) return@launch
                if (words != null && words != endings) render()
            }
            loading = false
            if (again && visible) load()
        }
    }

    private fun render() {
        val data = loaded ?: return
        endings = runLog.last
        val next = StatsModel.build(data.records, accounts, endings, period, accountId, System.currentTimeMillis(), zone, data.corrupt)
        model = next
        status = next.statusLine
    }

    private fun openSession(sessionId: String) {
        model?.sessions?.firstOrNull { it.sessionId == sessionId }?.let { raids = it }
    }

    /**
     * Every recorded battle, read fresh, so a click during a load is never lost. The phone wrote
     * to Downloads; the PC asks where, starting in Downloads with the same file name.
     */
    private fun export() {
        if (exporting) return
        exporting = true
        val names = accounts
        val now = System.currentTimeMillis()
        host.scope.launch {
            val read = runCatching {
                withContext(Dispatchers.IO) {
                    val records = BattleHistoryStore(historyDir).load().records
                    if (records.isEmpty()) null else HistoryCsv.of(records, names, zone)
                }
            }
            if (!isActive) return@launch
            read.exceptionOrNull()?.let { warn("CSV export failed", it) }
            val csv = read.getOrNull()
            if (csv == null) {
                exporting = false
                host.toast(if (read.isSuccess) "No raids to export yet" else EXPORT_FAILED, long = true)
                return@launch
            }
            val file = CsvExport.pickFile(HistoryCsv.fileName(now, zone))
            if (file == null) {
                exporting = false
                return@launch
            }
            val saved = runCatching { withContext(Dispatchers.IO) { CsvExport.save(file, csv) } }
            if (!isActive) return@launch
            exporting = false
            saved.exceptionOrNull()?.let { warn("CSV export failed", it) }
            // "Saved to Downloads" as on the phone when the user keeps the folder the dialog opened on.
            val folder = file.absoluteFile.parentFile?.let { it.name.ifEmpty { it.path } }
            host.toast(if (saved.isSuccess) "Saved to $folder" else EXPORT_FAILED, long = true)
        }
    }

    /** The phone's logcat warning, on standard error. */
    private fun warn(message: String, error: Throwable) {
        System.err.println("StatsScreen: $message: $error")
    }

    private companion object {
        const val PAGE = 20
        const val CACHE_MS = 30_000L
        const val MAX_FILTER_ACCOUNTS = 3
        const val EXPORT_FAILED = "Export failed. Check free space on the disk."
    }
}
