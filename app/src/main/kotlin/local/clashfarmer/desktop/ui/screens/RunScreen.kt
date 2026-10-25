package local.clashfarmer.desktop.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.core.Launch
import local.clashfarmer.core.Length
import local.clashfarmer.core.Policy
import local.clashfarmer.core.PresetStore
import local.clashfarmer.core.RunPlan
import local.clashfarmer.desktop.ui.DashboardHost
import local.clashfarmer.desktop.ui.DashboardScreen
import local.clashfarmer.desktop.ui.NOT_RUNNING
import local.clashfarmer.desktop.ui.StartFix
import local.clashfarmer.desktop.ui.components.Card
import local.clashfarmer.desktop.ui.components.Chip
import local.clashfarmer.desktop.ui.components.DangerButton
import local.clashfarmer.desktop.ui.components.FORM_MAX_WIDTH
import local.clashfarmer.desktop.ui.components.PrimaryButton
import local.clashfarmer.desktop.ui.components.ScreenPage
import local.clashfarmer.desktop.ui.components.ScreenTitle
import local.clashfarmer.desktop.ui.components.SecondaryButton
import local.clashfarmer.desktop.ui.components.SectionHeader
import local.clashfarmer.desktop.ui.components.Segmented
import local.clashfarmer.desktop.ui.components.Sheet
import local.clashfarmer.desktop.ui.components.StartReason
import local.clashfarmer.desktop.ui.components.Stepper
import local.clashfarmer.desktop.ui.components.StickyFooter
import local.clashfarmer.desktop.ui.components.TextInput
import local.clashfarmer.desktop.ui.components.TimeDialog
import local.clashfarmer.desktop.ui.components.ToggleRow
import local.clashfarmer.desktop.ui.components.Tooltip
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.insight.Format
import local.clashfarmer.service.FarmerService
import local.clashfarmer.service.PendingStart
import local.clashfarmer.ui.DashboardLoader
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.screens.ComposerState
import local.clashfarmer.ui.screens.HomeCards
import java.time.ZoneId

/**
 * Run (spec 2.3): one composer that edits a [RunPlan] and starts it with `plan:<json>`. Presets
 * load into it; the old single-purpose tools sit under More tools.
 */
class RunScreen(private val host: DashboardHost) : DashboardScreen {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val loader = DashboardLoader(host.filesDir)
    private val scroll = ScrollState(0)

    private var state: DashboardState? = null
    private var plan: RunPlan? = null
    /** False until the user changes something; until then the plan follows the last started one. */
    private var touched = false
    private var presets: List<RunPlan> by mutableStateOf(emptyList())
    private var last: RunPlan? = null
    private var presetsLoaded = false
    private var loadingPresets = false
    /** An account picked here, shown until the next tick carries the new selection. */
    private var pendingSelect: Pair<String, Long>? = null
    private var attacksMemory = Policy.LONG_RUN_ATTACKS
    private var minutesMemory = RunPlan.MAX_MINUTES
    private var endByMemory: Length.EndBy? = null
    // Start at: off each time the screen is made; the service keeps the start once it is set.
    private var startAt: Pair<Int, Int>? = null
    private var toolsOpen by mutableStateOf(false)
    private var visible = false

    /** The last render, which [Content] draws. */
    private var shown: Shown? by mutableStateOf(null)
    /** The open time dialog, the phone's TimePickerDialog. */
    private var picker: TimePick? by mutableStateOf(null)
    /** The plan the "+ Save" sheet is naming, while it is open. */
    private var saving: RunPlan? by mutableStateOf(null)
    /** The preset the rename sheet is open on. */
    private var editing: RunPlan? by mutableStateOf(null)

    /** Everything one render worked out from the plan and the state. */
    private class Shown(
        val plan: RunPlan,
        val composer: ComposerState,
        val busy: Boolean,
        val at: Long?,
        val atProblem: String?,
        val pending: PendingStart?,
        val startAtLine: String?,
        val tools: List<Tool>,
        /** An idle screen is not rebound on a clock, so an end or start time counts down on one here. */
        val clock: Boolean,
        /** How many accounts there are: with fewer than two, nothing offers a choice or Both. */
        val accountCount: Int,
        /** The link under a Start that is off, or null. */
        val fix: StartFix?
    )

    /** One More tools row: what it does, its button, and why it is off (null when it is on). */
    private class Tool(val title: String, val button: String, val why: String?, val action: () -> Unit)

    /** A time dialog: its title, the time it opens on, and what Set does with the time. */
    private class TimePick(val title: String, val hour: Int, val minute: Int, val onSet: (Int, Int) -> Unit)

    override fun onShown() {
        loadPresets()
        state?.let { render(it) }
    }

    override fun bind(state: DashboardState) {
        this.state = state
        if (!visible) return
        if (!presetsLoaded) loadPresets()
        render(state)
    }

    @Composable
    override fun Content() {
        DisposableEffect(Unit) {
            visible = true
            onDispose { visible = false }
        }
        val shown = shown ?: return
        val s = shown.composer
        LaunchedEffect(shown.clock) {
            if (!shown.clock) return@LaunchedEffect
            while (true) {
                delay(CLOCK_TICK_MS)
                state?.let { render(it) }
            }
        }
        Column(Modifier.fillMaxSize()) {
            ScreenPage(Modifier.weight(1f), scroll, maxWidth = FORM_MAX_WIDTH) {
                ScreenTitle("New run")
                PresetRow(shown.plan, shown.accountCount)

                // One account has nothing to choose: the summary names it, and Both cannot run.
                if (s.accountOptions.isEmpty()) {
                    SectionHeader("Account")
                    Label("No account yet. Add one in Setup.", size = FarmerTheme.BODY, color = FarmerColors.muted)
                } else if (shown.accountCount >= 2) {
                    SectionHeader("Account")
                    Segmented(
                        s.accountOptions,
                        s.accountIndex,
                        enabled = { i -> !shown.busy && (i != s.accountOptions.lastIndex || s.bothEnabled) }
                    ) { pickAccount(it) }
                }

                LengthControls(s)

                ToggleRow("Walls", s.walls, Modifier.padding(top = 12.dp), subtitle = s.wallsSubtitle,
                    disabledReason = s.wallsDisabledReason) { on -> edit { it.copy(walls = on, name = null) } }

                SectionHeader("Stop when")
                Segmented(ComposerState.STOPS, s.stopIndex, enabled = { it != 2 || s.accountGoalEnabled }) { i ->
                    edit { it.copy(stopWhen = ComposerState.stopAt(i), name = null) }
                }
                s.stopNote?.let {
                    Label(it, Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted)
                }

                if (s.armyVisible) {
                    SectionHeader("Army")
                    Segmented(ComposerState.ARMIES, s.armyIndex, enabled = { it != 1 || s.eventTroopsEnabled }) { i ->
                        edit { it.copy(eventTroops = i == 1, name = null) }
                    }
                }

                StartAt(shown)

                Card(Modifier.padding(top = 16.dp)) {
                    Label(s.summary, size = FarmerTheme.BODY, maxLines = 4)
                    s.problems.forEach {
                        Chip(it.text, it.tone, Modifier.padding(top = 8.dp), dot = true)
                    }
                }

                SectionHeader("More tools", action = if (toolsOpen) "Hide" else "Show", onAction = { toolsOpen = !toolsOpen })
                if (toolsOpen) shown.tools.forEach { ToolRow(it) }
            }
            StickyFooter(FORM_MAX_WIDTH) {
                PrimaryButton(
                    if (shown.at == null) "Start run" else "Start at ${Format.time(shown.at, zone)}",
                    Modifier.fillMaxWidth(),
                    enabled = s.startEnabled && shown.atProblem == null
                ) { start() }
                StartFix.plainReason(s.startReason)?.let { reason ->
                    StartReason(reason, shown.fix?.label) { shown.fix?.let(::fix) }
                }
            }
        }
        picker?.let { pick ->
            key(pick) {
                TimeDialog(
                    pick.title,
                    pick.hour,
                    pick.minute,
                    onSet = { hour, minute ->
                        picker = null
                        pick.onSet(hour, minute)
                    },
                    onDismiss = { picker = null }
                )
            }
        }
        saving?.let { SaveSheet(it) }
        editing?.let { preset -> preset.name?.let { PresetSheet(it) } }
    }

    /**
     * The presets, wrapped onto as many lines as they need (the phone scrolled them sideways),
     * then "+ Save". A click loads one; a right-click offers rename and delete, the phone's long
     * press, which a tooltip names. With fewer than two accounts the Both presets are left out:
     * they could only be refused.
     */
    @OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    @Composable
    private fun PresetRow(plan: RunPlan, accountCount: Int) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            shownPresets(presets, accountCount).forEach { preset ->
                val name = preset.name.orEmpty()
                ContextMenuArea(items = { listOf(ContextMenuItem("Rename or delete") { editing = preset }) }) {
                    TooltipArea(tooltip = { Tooltip("Right-click to rename or delete") }, delayMillis = 700) {
                        if (preset.name != null && preset == plan) PrimaryButton(name, compact = true) { load(preset) }
                        else SecondaryButton(name, compact = true) { load(preset) }
                    }
                }
            }
            SecondaryButton("+ Save", compact = true) { saving = plan }
        }
    }

    /** Length: the mode, then its attack steppers, minutes or end time. */
    @Composable
    private fun LengthControls(s: ComposerState) {
        SectionHeader("Length")
        Segmented(
            ComposerState.LENGTHS,
            s.lengthIndex,
            enabled = { i -> s.timeModesEnabled || (i != ComposerState.TIME && i != ComposerState.END_BY) }
        ) { pickLength(it) }
        // Both accounts: each one's attacks under its name.
        val names = s.bothNames?.takeIf { s.attackCountEnabled }
        names?.let {
            Label(it.first, Modifier.padding(top = 8.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
        }
        if (s.attackCountEnabled) {
            Stepper(s.attacks ?: Policy.LONG_RUN_ATTACKS, 1, RunPlan.MAX_ATTACKS, Modifier.padding(top = 4.dp),
                format = { Format.count(it, "attack") }) { next ->
                edit { p ->
                    p.copy(length = Length.Attacks(ComposerState.stepAttacks((p.length as? Length.Attacks)?.n ?: next, next)), name = null)
                }
            }
        }
        names?.let {
            Label(it.second, Modifier.padding(top = 8.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
            Stepper(s.secondAttacks ?: Policy.LONG_RUN_ATTACKS, 1, RunPlan.MAX_ATTACKS, Modifier.padding(top = 4.dp),
                format = { Format.count(it, "attack") }) { next ->
                edit { p -> p.copy(secondAttacks = ComposerState.stepAttacks(p.secondAttacks ?: next, next), name = null) }
            }
            Label("Stops early at ${Format.minutes(RunPlan.COMBINED_MINUTES)}.", Modifier.padding(top = 6.dp),
                size = FarmerTheme.SMALL, color = FarmerColors.muted)
        }
        s.minutes?.let { minutes ->
            Stepper(minutes, 5, RunPlan.MAX_MINUTES, Modifier.padding(top = 8.dp),
                format = { Format.minutes(it) }) { next ->
                edit { p ->
                    val prev = (p.length as? Length.Minutes)?.m ?: next
                    p.copy(length = Length.Minutes(ComposerState.stepMinutes(prev, next)), name = null)
                }
            }
        }
        if (s.endBy != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(s.endByLine.orEmpty(), Modifier.weight(1f).padding(end = 10.dp), size = FarmerTheme.BODY, maxLines = 2)
                SecondaryButton("Change", compact = true) { pickEndBy() }
            }
        }
    }

    /** The Start at row: off, a time to set, or the start already set with its Cancel. */
    @Composable
    private fun StartAt(shown: Shown) {
        ToggleRow(
            "Start at",
            shown.at != null,
            Modifier.padding(top = 12.dp),
            subtitle = if (shown.at == null) "Off: the run starts when you press Start" else "The PC and its screen stay on until then. Leave it unlocked",
            disabledReason = shown.pending?.let { "A start is set for ${Format.time(it.atMs, zone)}; cancel it to set another" }
        ) { on -> toggleStartAt(on) }
        shown.startAtLine?.let { line ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(line, Modifier.weight(1f).padding(end = 10.dp), size = FarmerTheme.BODY, maxLines = 2)
                SecondaryButton(if (shown.pending != null) "Cancel" else "Change", compact = true) { startAtAction() }
            }
        }
    }

    @Composable
    private fun ToolRow(tool: Tool) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Label(tool.title, size = FarmerTheme.BODY, maxLines = 2)
                tool.why?.let { Label(it, size = FarmerTheme.SMALL, color = FarmerColors.warn, maxLines = 2) }
            }
            SecondaryButton(tool.button, enabled = tool.why == null, compact = true, onClick = tool.action)
        }
    }

    /** "+ Save": names the composer's plan and keeps it, up to six presets. */
    @Composable
    private fun SaveSheet(current: RunPlan) {
        var field by remember { mutableStateOf(selectedAll(current.name ?: HomeCards.planName(current).replaceFirstChar { it.uppercase() })) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        fun save() {
            val name = field.text.trim()
            if (name.isEmpty()) {
                host.toast("Give the preset a name")
                return
            }
            val full = presets.size >= PresetStore.MAX_PRESETS && presets.none { it.name == name }
            val named = current.copy(name = name)
            changePresets({ it.save(named) }) { ok ->
                if (ok) {
                    touched = true
                    plan = named
                    saving = null
                } else {
                    host.toast(if (full) "Six presets saved; delete one first" else "Could not save the preset")
                }
            }
        }
        Sheet("Save preset", onClose = { saving = null }, width = 560.dp, actions = {
            PrimaryButton("Save", compact = true, onClick = ::save)
        }) {
            Label("Saves the run as set now. Click it later to load it; right-click it to rename or delete.",
                Modifier.padding(top = 12.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
            TextInput(field, { field = it }, Modifier.padding(top = 12.dp).focusRequester(focus), hint = "Preset name",
                maxLength = ComposerState.MAX_NAME, onSubmit = ::save)
        }
    }

    /** Right-click on a preset: rename or delete the preset called [name]. */
    @Composable
    private fun PresetSheet(name: String) {
        var field by remember { mutableStateOf(selectedAll(name)) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        fun rename() {
            val to = field.text.trim()
            if (to.isEmpty() || to == name) {
                editing = null
                return
            }
            changePresets({ it.rename(name, to) }) { ok ->
                if (ok) {
                    if (plan?.name == name) plan = plan?.copy(name = to)
                    editing = null
                } else {
                    host.toast("A preset is already called $to")
                }
            }
        }
        Sheet(name, onClose = { editing = null }, width = 560.dp, actions = {
            PrimaryButton("Rename", compact = true, onClick = ::rename)
        }) {
            Label("Name", Modifier.padding(top = 12.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
            TextInput(field, { field = it }, Modifier.padding(top = 6.dp).focusRequester(focus), hint = "Preset name",
                maxLength = ComposerState.MAX_NAME, onSubmit = ::rename)
            DangerButton("Delete preset", Modifier.padding(top = 24.dp).fillMaxWidth()) {
                changePresets({ it.delete(name) }) { ok ->
                    if (ok) {
                        if (plan?.name == name) plan = plan?.copy(name = null)
                        editing = null
                    } else {
                        host.toast("Could not delete the preset")
                    }
                }
            }
        }
    }

    /** The selection to show: one picked here wins for up to 3 s, until a tick carries it. */
    private fun selectedId(state: DashboardState): String? {
        val pending = pendingSelect ?: return state.selectedId
        if (state.selectedId == pending.first || System.currentTimeMillis() - pending.second > 3_000) {
            pendingSelect = null
            return state.selectedId
        }
        return pending.first
    }

    private fun render(state: DashboardState) {
        val now = System.currentTimeMillis()
        val selectedId = selectedId(state)
        if (!touched) plan = ComposerState.initial(last, state.accounts, selectedId)
        val plan = plan ?: return
        val at = startAt?.let { (hour, minute) -> PendingStart.nextAt(hour, minute, now, zone) }
        // End by is judged from the start time when one is set, as the start itself judges it.
        val s = ComposerState.of(plan, state.accounts, state.profiles, selectedId, state.connected, state.busy, at ?: now, zone, fromStart = at != null)
        keepLength(plan)
        val pending = FarmerService.instance?.pendingStart
        val atProblem = at?.let { PendingStart.problem(it, now) }
        shown = Shown(
            plan = plan,
            composer = s,
            busy = state.busy,
            at = at,
            atProblem = atProblem,
            pending = pending,
            startAtLine = startAtLine(pending, at, atProblem, now),
            tools = tools(state, selectedId),
            clock = (s.endBy != null || at != null || pending != null) && !state.busy,
            accountCount = state.accounts.size,
            fix = StartFix.of(plan, state.accounts, state.profiles, selectedId, state.connected, state.busy, s.startReason)
        )
    }

    /**
     * Follows the link under Start. The supervised attack is under More tools on this page, so it
     * opens them and scrolls down to them.
     */
    private fun fix(fix: StartFix) {
        when (fix) {
            is StartFix.Calibrate -> host.openCalibration(fix.accountId)
            is StartFix.Open -> host.openTab(fix.tab)
            is StartFix.SupervisedAttack -> {
                toolsOpen = true
                host.scope.launch {
                    // A frame for the opened tools to lay out, so the page's end is below them.
                    delay(50)
                    scroll.animateScrollTo(scroll.maxValue)
                }
            }
        }
    }

    /** The line under Start at: the start already set, the time picked, or nothing. */
    private fun startAtLine(pending: PendingStart?, at: Long?, problem: String?, now: Long): String? = when {
        pending != null -> "Set for ${Format.time(pending.atMs, zone)}: ${pending.label}. Stop cancels it while it waits."
        at != null -> {
            val minutes = ((at - now + 59_999) / 60_000).toInt()
            val clock = Format.time(at, zone)
            if (problem == null) "Starts at $clock, in ${Format.minutes(minutes)}"
            else "$clock is ${Format.minutes(minutes)} away; pick a time within 12 hours"
        }
        else -> null
    }

    private fun toggleStartAt(on: Boolean) {
        val state = state ?: return
        startAt = if (on) ComposerState.defaultEndBy(System.currentTimeMillis(), zone).let { it.hour to it.minute } else null
        render(state)
        if (on) pickStartAt()
    }

    private fun pickStartAt() {
        val (hour, minute) = startAt ?: return
        picker = TimePick("Start at", hour, minute) { h, m ->
            startAt = h to m
            state?.let { render(it) }
        }
    }

    /** Cancel while a start is set, else Change. Cancelling writes the file and the log, so off the UI thread. */
    private fun startAtAction() {
        val service = FarmerService.instance
        if (service?.pendingStart == null) {
            pickStartAt()
            return
        }
        host.scope.launch {
            withContext(Dispatchers.IO) { runCatching { service.cancelPendingStart() } }
            if (!isActive) return@launch
            state?.let { render(it) }
        }
    }

    /** Keeps the last attack count, minutes and end time, so switching length and back restores them. */
    private fun keepLength(plan: RunPlan) {
        when (val l = plan.length) {
            is Length.Attacks -> attacksMemory = l.n
            is Length.Minutes -> minutesMemory = l.m
            is Length.EndBy -> endByMemory = l
        }
    }

    /**
     * More tools, each with why it is off, as the phone's showTools set them. With one account
     * there is nothing to switch to, so the switch tool is left out.
     */
    private fun tools(state: DashboardState, selectedId: String?): List<Tool> {
        val account = state.accounts.firstOrNull { it.id == selectedId }
        val profile = selectedId?.let { state.profiles[it] }
        val off = when {
            state.busy -> "A run is already going"
            !state.connected -> NOT_RUNNING
            else -> null
        }
        val calibration = when {
            account == null -> "Choose an account first"
            profile == null -> "${account.villageName} is not calibrated"
            else -> null
        }
        // The validators' own messages name anchors and ROIs; say what to do instead.
        val attackError = profile?.takeIf { runCatching { it.validateForAttack() }.isFailure }
            ?.let { "Calibration is incomplete; finish it in Setup" }
        val wallError = profile?.takeIf { runCatching { it.validateForWalls() }.isFailure }
            ?.let { "Wall calibration is incomplete; finish it in Setup" }
            ?: account?.settings?.wallCapacityProblem()
        return listOfNotNull(
            Tool("Supervised attack (one attack you watch)", "Start", off ?: calibration ?: attackError) { host.act("supervised") },
            Tool("Walls only, up to ${Format.minutes(Launch.WALLS_ONLY_MINUTES)}", "Start", off ?: calibration ?: wallError) {
                host.act("walls")
            },
            if (state.accounts.size < 2) null
            else Tool("Switch account in Clash", "Switch", off ?: if (account == null) "Choose an account first" else null) {
                this.state?.selectedId?.let { host.act("switch_only:$it") }
            },
            Tool("Inspect the game screen", "Inspect", off) { host.act("inspect") }
        )
    }

    /** Applies a user edit to the plan and redraws. */
    private fun edit(change: (RunPlan) -> RunPlan) {
        val state = state ?: return
        val current = plan ?: return
        touched = true
        plan = change(current)
        render(state)
    }

    private fun pickAccount(index: Int) {
        val state = state ?: return
        val shown = state.accounts.take(ComposerState.MAX_ACCOUNT_OPTIONS)
        if (index >= shown.size) {
            if (plan?.isBoth != true) edit { ComposerState.withBoth(it) }
            return
        }
        val account = shown[index]
        // A click on the account already planned changes nothing, so walls and stop rule stay as set.
        val current = plan?.resolve(state.accounts, selectedId(state))
        if (current != null && !current.isBoth && current.accountIds.firstOrNull() == account.id) return
        if (account.id != selectedId(state)) {
            pendingSelect = account.id to System.currentTimeMillis()
            host.act("select:${account.id}")
        }
        edit { ComposerState.withSingle(it, account) }
    }

    private fun pickLength(index: Int) {
        val endBy = endByMemory ?: ComposerState.defaultEndBy(System.currentTimeMillis(), zone)
        edit { ComposerState.withLength(it, index, attacksMemory, minutesMemory, endBy) }
    }

    private fun pickEndBy() {
        val current = (plan?.length as? Length.EndBy) ?: ComposerState.defaultEndBy(System.currentTimeMillis(), zone)
        picker = TimePick("End by", current.hour, current.minute) { hour, minute ->
            edit { it.copy(length = Length.EndBy(hour, minute), name = null) }
        }
    }

    private fun load(preset: RunPlan) {
        val state = state ?: return
        touched = true
        plan = preset
        render(state)
    }

    private fun start() {
        val plan = plan ?: return
        val (hour, minute) = startAt ?: run {
            host.act("plan:${plan.toJson()}")
            return
        }
        val service = FarmerService.instance ?: return host.toast(NOT_RUNNING)
        val atMs = PendingStart.nextAt(hour, minute, System.currentTimeMillis(), zone)
        host.scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { service.scheduleStart(plan, atMs) }.exceptionOrNull() }
            if (!isActive) return@launch
            if (error == null) {
                startAt = null
                host.toast("The run starts at ${Format.time(atMs, zone)}")
            } else {
                host.toast(error.message ?: "Could not set the start")
            }
            state?.let { render(it) }
        }
    }

    private fun loadPresets() {
        if (loadingPresets) return
        loadingPresets = true
        host.scope.launch {
            val loaded = runCatching { withContext(Dispatchers.IO) { loader.presets() } }.getOrNull()
            loadingPresets = false
            if (!isActive) return@launch
            if (loaded != null) {
                presets = loaded.presets
                last = loaded.last
            }
            presetsLoaded = true
            // Rendered even before the page is drawn: a fast read can beat the first frame.
            state?.let { render(it) }
        }
    }

    /** Runs a preset change on IO and rereads the row; [onDone] gets whether the store accepted it. */
    private fun changePresets(change: (PresetStore) -> Boolean, onDone: (Boolean) -> Unit) {
        host.scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val store = PresetStore(host.filesDir)
                    change(store) to store.presets()
                }
            }.getOrNull()
            if (!isActive) return@launch
            result?.second?.let { presets = it }
            onDone(result?.first == true)
            state?.let { render(it) }
        }
    }

    /** [text] with all of it selected, so typing replaces it. */
    private fun selectedAll(text: String) = TextFieldValue(text, TextRange(0, text.length))

    private companion object {
        const val CLOCK_TICK_MS = 30_000L
    }
}

/** The presets Run offers: with fewer than two accounts a Both preset could only be refused, so it is left out. */
internal fun shownPresets(presets: List<RunPlan>, accountCount: Int): List<RunPlan> =
    if (accountCount < 2) presets.filterNot { it.isBoth } else presets
