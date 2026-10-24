package local.clashfarmer.desktop.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.desktop.ui.DashboardHost
import local.clashfarmer.desktop.ui.DashboardScreen
import local.clashfarmer.desktop.ui.components.Card
import local.clashfarmer.desktop.ui.components.Divider
import local.clashfarmer.desktop.ui.components.Dot
import local.clashfarmer.desktop.ui.components.Gap
import local.clashfarmer.desktop.ui.components.PrimaryButton
import local.clashfarmer.desktop.ui.components.ScreenPage
import local.clashfarmer.desktop.ui.components.ScreenTitle
import local.clashfarmer.desktop.ui.components.SecondaryButton
import local.clashfarmer.desktop.ui.components.SectionHeader
import local.clashfarmer.desktop.ui.components.Segmented
import local.clashfarmer.desktop.ui.components.Sheet
import local.clashfarmer.desktop.ui.components.hoverClick
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.ink
import local.clashfarmer.insight.ActivityProblems
import local.clashfarmer.insight.EventText
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.Problem
import local.clashfarmer.insight.Problems
import local.clashfarmer.insight.RunGroup
import local.clashfarmer.insight.Timeline
import local.clashfarmer.insight.TimelineLine
import local.clashfarmer.log.LogTail
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.Tab
import local.clashfarmer.ui.TabBadge
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.components.UiText
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import local.clashfarmer.insight.Tone as InsightTone

/**
 * Activity (spec 2.5): what needs you, then what happened, run by run.
 *
 * The inbox and the timeline come from [Problems] and [Timeline] over today's and yesterday's log
 * tail ([LogTail]) merged with the live buffer each bind hands over. The tail is read on IO at
 * most every 30 s, and the model is rebuilt off the UI thread when the events change, at most
 * every 2 s, so bind stays cheap. Dismissals are kept in files/problems-dismissed.json.
 */
class ActivityScreen(private val host: DashboardHost) : DashboardScreen {
    private val scroll = ScrollState(0)

    /** One build's result. [events] is the merged log, oldest first, for the technical log. */
    private class Model(val events: List<JSONObject>, val groups: List<RunGroup>, val problems: List<Problem>)

    private class Built(val tail: List<JSONObject>?, val readAt: Long, val capped: Boolean, val dismissed: Map<String, Long>, val model: Model)

    /** A problem's button: its words and what a click does. */
    private class RowAction(val label: String, val run: () -> Unit)

    private var state: DashboardState? = null
    private var model: Model? by mutableStateOf(null)
    private var tail: List<JSONObject> = emptyList()
    private var tailReadAt = 0L
    /** The tail filled [LogTail.MAX_LINES], so it starts partway through the two days. */
    private var tailCapped = false
    private var builtAt = 0L
    private var builtFrom: Triple<List<String>, List<AccountRecord>, Map<String, PhoneProfile?>>? = null
    private var dirty = true
    private var job: Job? = null
    private var dismissed: Map<String, Long> = emptyMap()
    /** The last build threw before any model existed, so "Reading the log." would never go away. */
    private var failed by mutableStateOf(false)

    private var problemsOnly by mutableStateOf(false)
    /** The day the page is drawn for, so "Today", "Yesterday" and the inbox times move on at midnight. */
    private var today by mutableStateOf(LocalDate.now(ZoneId.systemDefault()))
    /** Groups the person opened or closed; the rest follow the default (newest run open). */
    private val opened = mutableStateMapOf<String, Boolean>()
    /** Open groups showing every line rather than the newest [MAX_LINES]. */
    private var showAll by mutableStateOf(emptySet<String>())
    /** The technical log's events, newest first, as they were when it opened; null while it is closed. */
    private var technical: List<JSONObject>? by mutableStateOf(null)
    /** The event whose sheet is open over the technical log. */
    private var event: JSONObject? by mutableStateOf(null)

    override val badge: TabBadge?
        get() = model?.problems?.size?.takeIf { it > 0 }?.let { TabBadge(it, Tone.BAD) }

    override fun bind(state: DashboardState) {
        this.state = state
        today = LocalDate.now(ZoneId.systemDefault())
        if (Triple(state.events, state.accounts, state.profiles) != builtFrom) dirty = true
        schedule()
    }

    override fun onShown() {
        today = LocalDate.now(ZoneId.systemDefault())
        // A clock set back makes the age negative; that tail is stale too.
        if (System.currentTimeMillis() - tailReadAt !in 0..CACHE_MS) dirty = true
        schedule()
    }

    @Composable
    override fun Content() {
        val m = model
        val zone = ZoneId.systemDefault()
        ScreenPage(scroll = scroll) {
            ScreenTitle("Activity")
            val count = m?.problems?.size ?: 0
            Segmented(listOf("Everything", if (count > 0) "Problems $count" else "Problems"), if (problemsOnly) 1 else 0) { picked ->
                problemsOnly = picked == 1
            }
            Gap(16.dp)
            Inbox(m?.problems.orEmpty(), zone)
            note(m)?.let { Label(it, Modifier.padding(bottom = 12.dp), size = FarmerTheme.BODY, color = FarmerColors.muted) }
            if (m != null && !problemsOnly) RunTimeline(m.groups, zone)
            SecondaryButton("Show technical log", Modifier.fillMaxWidth()) {
                technical = model?.events.orEmpty().asReversed()
            }
        }
        technical?.let { TechnicalSheet(it, zone) }
        event?.let { e -> key(e) { EventSheet(e) { event = null } } }
    }

    // ----- Loading -----

    /** Rebuilds the model on IO while it is out of date; one build at a time, at most every [MIN_BUILD_MS]. */
    private fun schedule() {
        if (job?.isActive == true || !dirty || state == null) return
        job = host.scope.launch {
            while (dirty) {
                val wait = MIN_BUILD_MS - (System.currentTimeMillis() - builtAt)
                if (wait > 0) delay(wait.coerceAtMost(MIN_BUILD_MS))
                val s = state ?: break
                dirty = false
                builtFrom = Triple(s.events, s.accounts, s.profiles)
                val now = System.currentTimeMillis()
                val cached = tail.takeIf { now - tailReadAt in 0..CACHE_MS }
                val capped = tailCapped
                val known = dismissed
                val files = host.filesDir
                val built = runCatching { withContext(Dispatchers.IO) { build(files, s, cached, capped, known, now) } }.getOrNull()
                // runCatching also catches cancellation; a closed window's screens must not be touched.
                if (!isActive) return@launch
                builtAt = System.currentTimeMillis()
                if (built == null) {
                    if (model == null) failed = true
                    continue
                }
                built.tail?.let { tail = it; tailReadAt = built.readAt; tailCapped = built.capped }
                // Keep a dismissal made while this build ran.
                dismissed = built.dismissed + dismissed
                val next = built.model
                // The tab's badge reads this state too, so the rail follows without a rebind.
                model = Model(next.events, next.groups, next.problems.filter { !Problems.isDismissed(it, dismissed) })
            }
        }
    }

    /** Runs on IO: reads what the model needs from files and builds it. [cachedTail] null reads the tail again. */
    private fun build(files: File, s: DashboardState, cachedTail: List<JSONObject>?, cachedCapped: Boolean, known: Map<String, Long>, now: Long): Built {
        val lines = if (cachedTail == null) LogTail.read(File(files, "logs"), now) else null
        val freshTail = lines?.let { Timeline.parse(it) }
        val capped = lines?.let { it.size >= LogTail.MAX_LINES } ?: cachedCapped
        // The live buffer is refilled from older days when the app starts, so it can hold events older
        // than the tail; sorting keeps the list oldest first for the technical log. The sort is stable.
        val events = LogTail.merge(freshTail ?: cachedTail.orEmpty(), s.events)
            .sortedBy { Format.parseTime(it.optString("time")) ?: Long.MIN_VALUE }
        // The remote page may dismiss too. A dismissal is never taken back, so the two sets are joined.
        val dismissedNow = (if (freshTail != null) readDismissed(files) else emptyMap()) + known

        val all = Timeline.group(events)
        // A capped tail's oldest stretch was cut at the cap. A run cut that way has lost its start and
        // groups as idle, so the timeline starts at the next whole group; Problems still see every event.
        val groups = if (capped && all.size > 1 && all.last().kind == RunGroup.Kind.IDLE) all.dropLast(1) else all
        val problems = ActivityProblems.inbox(files, s.accounts, s.profiles, events, dismissedNow, ZoneId.systemDefault())
        return Built(freshTail, now, capped, dismissedNow, Model(events, groups, problems))
    }

    // ----- Dismissals -----

    private fun dismiss(problems: List<Problem>) {
        val now = System.currentTimeMillis()
        val next = Problems.dismissAll(dismissed, problems, now)
        if (next == dismissed) return
        dismissed = next
        model = model?.let { m -> Model(m.events, m.groups, m.problems.filter { !Problems.isDismissed(it, next) }) }
        val files = host.filesDir
        host.scope.launch(Dispatchers.IO) { runCatching { saveDismissed(files, next, now) } }
    }

    private fun readDismissed(files: File): Map<String, Long> = runCatching {
        Problems.dismissedFromJson(File(files, Problems.FILE_NAME).takeIf { it.isFile }?.readText())
    }.getOrDefault(emptyMap())

    /** Joins [dismissed] with what the file holds now and replaces the file in one rename. */
    private fun saveDismissed(files: File, dismissed: Map<String, Long>, now: Long) {
        synchronized(FILE_LOCK) {
            val text = Problems.dismissedToJson(readDismissed(files) + dismissed, now)
            val file = File(files, Problems.FILE_NAME)
            val temp = File(files, Problems.FILE_NAME + ".tmp")
            temp.writeText(text)
            // File.renameTo will not replace an existing file on Windows; Files.move does.
            val moved = runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.isSuccess
            if (!moved) {
                file.writeText(text)
                temp.delete()
            }
        }
    }

    // ----- Inbox -----

    /** The line under the inbox: loading, a read error, or why the list is empty; null when it has rows. */
    private fun note(m: Model?): String? = when {
        m == null -> if (failed) "The log could not be read. It is read again when something new is logged." else "Reading the log."
        problemsOnly && m.problems.isEmpty() -> "Nothing needs you right now."
        !problemsOnly && m.groups.isEmpty() -> "Nothing logged today or yesterday. Start a run or inspect the game to see it here."
        else -> null
    }

    @Composable
    private fun Inbox(problems: List<Problem>, zone: ZoneId) {
        if (problems.isEmpty()) return
        SectionHeader("Needs you")
        Card(padding = 0.dp) {
            Column(Modifier.padding(start = FarmerSize.GUTTER, top = 2.dp, end = 12.dp, bottom = 2.dp)) {
                problems.forEachIndexed { index, problem ->
                    if (index > 0) Divider()
                    ProblemRow(problem, full = problemsOnly, zone)
                }
            }
        }
        if (problems.any { it.dismissible }) {
            PrimaryButton("Clear resolved", Modifier.fillMaxWidth()) { dismiss(problems) }
        }
        Gap(16.dp)
    }

    /**
     * One problem. In Everything it is one line with its action; clicking the line opens the
     * Problems filter, which shows each problem in full with every action it has.
     */
    @Composable
    private fun ProblemRow(p: Problem, full: Boolean, zone: ZoneId) {
        val action = action(p)
        val click = if (full) Modifier else Modifier
            .defaultMinSize(minHeight = 56.dp)
            .hoverClick(shape = RoundedCornerShape(8.dp)) { problemsOnly = true }
        Column(Modifier.fillMaxWidth().then(click).padding(horizontal = 4.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(p.tone.ui().ink)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Label(p.title, size = FarmerTheme.BODY, bold = true, maxLines = if (full) Int.MAX_VALUE else 2)
                    whenText(p.timeMs, zone)?.let {
                        Label(it, Modifier.padding(top = 3.dp), size = FarmerTheme.LABEL, color = FarmerColors.faint)
                    }
                }
                if (!full && action != null) {
                    SecondaryButton(action.label, Modifier.padding(start = 8.dp), compact = true, onClick = action.run)
                }
            }
            if (full) {
                Label(p.detail, Modifier.padding(start = 18.dp, top = 8.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
                val dismissible = p.dismissible && p.action != Problem.Action.DISMISS
                if (action != null || dismissible) {
                    Row(Modifier.padding(start = 18.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (action != null) SecondaryButton(action.label, compact = true, onClick = action.run)
                        if (dismissible) SecondaryButton("Dismiss", compact = true) { dismiss(listOf(p)) }
                    }
                }
            }
        }
    }

    /** The problem's own action: a pending marker is cleared only through the existing village check. */
    private fun action(p: Problem): RowAction? = when (p.action) {
        Problem.Action.CHECK_VILLAGE -> p.accountId?.let { id -> RowAction("Check village") { host.act("acknowledge_wall:$id") } }
        Problem.Action.OPEN_SETUP -> RowAction("Open Setup") { host.openTab(Tab.SETUP) }
        Problem.Action.DISMISS -> if (p.dismissible) RowAction("Dismiss") { dismiss(listOf(p)) } else null
    }

    // ----- Timeline -----

    /** The run and idle groups, newest first, under a header for each day. */
    @Composable
    private fun RunTimeline(groups: List<RunGroup>, zone: ZoneId) {
        val newestRun = groups.firstOrNull { it.kind == RunGroup.Kind.RUN }
        val keys = groupKeys(groups)
        var lastDay: LocalDate? = null
        groups.forEachIndexed { i, g ->
            val day = g.startMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            if (day != null && day != lastDay) {
                SectionHeader(dayTitle(day, g.startMs, zone))
                lastDay = day
            }
            val id = keys[i]
            key(id) { GroupCard(g, id, opened[id] ?: (g === newestRun), id in showAll, zone) }
        }
    }

    /** A key per group that survives a rebuild, so a group the person opened stays open. */
    private fun groupKeys(groups: List<RunGroup>): List<String> {
        val taken = HashSet<String>()
        return groups.map { g ->
            var id = "${g.kind}:${g.startMs}:${g.accountName}"
            while (id in taken) id += "+"
            taken += id
            id
        }
    }

    private fun dayTitle(day: LocalDate, ms: Long?, zone: ZoneId): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> Format.day(ms, zone)
    }

    /** A run or idle stretch: a header that opens and closes it, then its lines newest first. */
    @Composable
    private fun GroupCard(g: RunGroup, id: String, open: Boolean, all: Boolean, zone: ZoneId) {
        Card(padding = 0.dp) {
            Column(Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = if (open) 12.dp else 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                        .hoverClick(shape = RoundedCornerShape(12.dp)) { opened[id] = !open }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Label("›", Modifier.width(20.dp).rotate(if (open) 90f else 0f), size = 22f, color = FarmerColors.muted,
                        align = TextAlign.Center)
                    Spacer(Modifier.width(4.dp))
                    Dot(g.tone.ui().ink)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Label(g.header(zone), size = FarmerTheme.BODY, bold = true, maxLines = 2)
                        g.summary()?.let {
                            Label(it, Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted)
                        }
                    }
                }
                if (open) {
                    val shown = if (all) g.lines else g.lines.take(MAX_LINES)
                    if (shown.isEmpty()) {
                        Label("Nothing to show for this run yet.", Modifier.padding(start = 8.dp, top = 4.dp),
                            size = FarmerTheme.SMALL, color = FarmerColors.muted)
                    }
                    shown.forEach { LineRow(it, zone) }
                    if (g.lines.size > shown.size) {
                        SecondaryButton("Show ${g.lines.size - shown.size} earlier", Modifier.padding(start = 8.dp, top = 8.dp),
                            compact = true) { showAll = showAll + id }
                    }
                }
            }
        }
    }

    /** "07:40  Stopped: storage full, no free builder", amber and red lines in their tone. */
    @Composable
    private fun LineRow(line: TimelineLine, zone: ZoneId) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 5.dp, bottom = 5.dp)) {
            Label(Format.time(line.timeMs, zone), Modifier.width(46.dp).padding(top = 2.dp), size = FarmerTheme.LABEL,
                color = FarmerColors.faint)
            val color = when (line.tone) {
                InsightTone.BAD -> FarmerColors.bad
                InsightTone.WARN -> FarmerColors.warn
                InsightTone.IDLE -> FarmerColors.muted
                else -> FarmerColors.text
            }
            Label(line.text, Modifier.weight(1f), size = FarmerTheme.SMALL + 1, color = color)
        }
    }

    // ----- Technical log -----

    /** Every merged event, newest first, a page at a time: its sentence, its raw name, and the JSON on click. */
    @Composable
    private fun TechnicalSheet(events: List<JSONObject>, zone: ZoneId) {
        var shown by remember(events) { mutableStateOf(minOf(events.size, TECH_PAGE)) }
        Sheet("Technical log", onClose = { technical = null }) {
            Label("${Format.count(events.size, "event")}, newest first. Click one for everything it recorded.",
                Modifier.padding(top = 12.dp, bottom = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted)
            events.take(shown).forEachIndexed { i, e ->
                if (i > 0) Divider()
                TechnicalRow(e, zone) { event = e }
            }
            if (shown < events.size) {
                SecondaryButton("Show older", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    shown = minOf(events.size, shown + TECH_PAGE)
                }
            }
        }
    }

    @Composable
    private fun TechnicalRow(e: JSONObject, zone: ZoneId, onClick: () -> Unit) {
        val name = remember(e) { EventText.nameOf(e) }
        val time = remember(e) { Format.parseTime(e.optString("time")) }
        val sentence = remember(e) { EventText.technical(e) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FarmerSize.CONTROL)
                .hoverClick(shape = RoundedCornerShape(8.dp), onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Label(Format.time(time, zone), Modifier.width(46.dp).padding(top = 2.dp), size = FarmerTheme.LABEL,
                color = FarmerColors.faint)
            Column(Modifier.weight(1f)) {
                Label(sentence, size = FarmerTheme.SMALL + 1)
                Label(name, Modifier.padding(top = 3.dp), size = FarmerTheme.LABEL, color = FarmerColors.faint, monospace = true)
            }
        }
    }

    /** One event over the technical log; closing it returns there. The JSON can be selected and copied. */
    @Composable
    private fun EventSheet(e: JSONObject, onClose: () -> Unit) {
        val sentence = remember(e) { EventText.technical(e) }
        val time = remember(e) { Format.parseTime(e.optString("time")) }
        val json = remember(e) { runCatching { e.toString(2) }.getOrDefault(e.toString()) }
        // A sheet title is one line, too short for most sentences, so the sentence leads the body.
        Sheet("Event", onClose) {
            Label(sentence, Modifier.padding(top = 12.dp), size = FarmerTheme.BODY, bold = true)
            Label(UiText.stamp(time), Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted)
            SelectionContainer {
                Label(json, Modifier.fillMaxWidth().padding(top = 12.dp), size = FarmerTheme.SMALL, monospace = true)
            }
        }
    }

    // ----- Small pieces -----

    /** "07:12" today, "Oct 7 22:30" another day; null when the problem has no time. */
    private fun whenText(ms: Long?, zone: ZoneId): String? {
        if (ms == null) return null
        val sameDay = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == today
        return if (sameDay) Format.time(ms, zone) else Format.dayTime(ms, zone)
    }

    private fun InsightTone.ui(): Tone = Tone.valueOf(name)

    private companion object {
        const val CACHE_MS = 30_000L
        const val MIN_BUILD_MS = 2_000L
        /** Lines an open group shows before "Show N earlier". */
        const val MAX_LINES = 120
        const val TECH_PAGE = 150
        val FILE_LOCK = Any()
    }
}
