package local.clashfarmer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.GoalMatch
import local.clashfarmer.core.Resource
import local.clashfarmer.desktop.ui.components.Card
import local.clashfarmer.desktop.ui.components.Gap
import local.clashfarmer.desktop.ui.components.PrimaryButton
import local.clashfarmer.desktop.ui.components.Segmented
import local.clashfarmer.desktop.ui.components.Sheet
import local.clashfarmer.desktop.ui.components.TextInput
import local.clashfarmer.desktop.ui.components.ToggleRow
import local.clashfarmer.desktop.ui.components.hoverClick
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.service.FarmerService
import local.clashfarmer.ui.AccountEditor
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.FarmingSettingsEditor
import local.clashfarmer.ui.PlacedErrors
import local.clashfarmer.ui.SettingsEdit
import local.clashfarmer.ui.SettingsFields
import local.clashfarmer.ui.SettingsSection

private const val BUSY_NOTE = "Settings cannot be saved while a run is active."

/** The scroll anchor of the errors no box owns, at the top of the sheet. */
private const val TOP_ERRORS = "top"

/**
 * The account settings editor (spec 2.6) without its views: what the boxes, switches and
 * sections hold, and Save. It edits the same fields the phone's did, and each error shows under
 * the box it is about. The controller makes one for `edit:<id>`; [AccountEditorSheet] shows it.
 */
class AccountEditorModel(private val host: DashboardHost, val account: AccountRecord) {

    private val initial = FarmingSettingsEditor.fieldsOf(account.settings)
    private val texts = mutableStateMapOf<String, String>().apply { putAll(FarmingSettingsEditor.textsOf(initial)) }
    private val errors = mutableStateMapOf<String, String>()

    /** Errors no box owns, shown at the top of the sheet. */
    var topErrors: List<String> by mutableStateOf(emptyList())
        private set

    var walls by mutableStateOf(initial.wallsEnabled)
    var currency by mutableStateOf(CURRENCIES.indexOf(initial.currencyPreference).coerceAtLeast(0))
    var goalMatch by mutableStateOf(if (initial.goalMatch == GoalMatch.ANY) 1 else 0)
    var opponents by mutableStateOf(initial.opponentEnabled)
    var earlyEnd by mutableStateOf(initial.earlyEndEnabled)
    var atDamage by mutableStateOf(initial.endAtDamage)
    var onStall by mutableStateOf(initial.endOnStall)
    var collectBefore by mutableStateOf(initial.collectBeforeFarming)
    var collectAfter by mutableStateOf(initial.collectAfterFarming)

    /** The open sections. Walls switched off on load open theirs, so the reason is in view. */
    private val expanded = mutableStateListOf<SettingsSection>().apply {
        if (account.settings.wallSetupRequired != null) add(SettingsSection.WALLS)
    }

    var saving by mutableStateOf(false)
        private set

    /**
     * Save found a run the last tick had not shown yet. The busy note shows, and Save stays off,
     * until the service is idle again.
     */
    var refusedBusy by mutableStateOf(false)

    /** The box (or the top errors) to scroll into view once its section has opened; null when none. */
    var scrollTarget: String? by mutableStateOf(null)

    /** Set when the sheet leaves the window, so a save that ends later cannot close the next sheet. */
    internal var closed = false

    fun text(key: String): String = texts[key].orEmpty()

    fun error(key: String): String? = errors[key]

    fun isExpanded(section: SettingsSection) = section in expanded

    fun toggle(section: SettingsSection) {
        if (!expanded.remove(section)) expanded += section
    }

    /** A box's new text. Its error goes, as the phone's did on any edit. */
    fun type(key: String, value: String) {
        texts[key] = value
        errors.remove(key)
    }

    /** The settings the sheet shows now, switches and boxes together. */
    fun current(): SettingsFields = FarmingSettingsEditor.withTexts(
        initial.copy(
            wallsEnabled = walls,
            reserveGold = "0",
            reserveElixir = "0",
            currencyPreference = CURRENCIES[currency],
            goalMatch = if (goalMatch == 1) GoalMatch.ANY else GoalMatch.ALL,
            opponentEnabled = opponents,
            earlyEndEnabled = earlyEnd,
            endAtDamage = atDamage,
            endOnStall = onStall,
            collectBeforeFarming = collectBefore,
            collectAfterFarming = collectAfter
        ),
        texts.toMap()
    )

    /** Reads every box and writes the settings when all of them read. [onSaved] closes the sheet. */
    fun save(onSaved: () -> Unit) {
        if (saving) return
        if (FarmerService.instance?.isBusy == true) {
            refusedBusy = true
            return
        }
        showErrors(PlacedErrors(emptyMap(), emptyList()))
        when (val edit = FarmingSettingsEditor.parseFor(account, current())) {
            is SettingsEdit.Invalid -> showErrors(FarmingSettingsEditor.place(edit.errors))
            is SettingsEdit.Valid -> write(edit.settings, onSaved)
        }
    }

    /**
     * Saves on IO. The account is read again first and only its settings replaced, so a run
     * result written while the sheet was open is kept.
     */
    private fun write(settings: AccountSettings, onSaved: () -> Unit) {
        saving = true
        val files = host.filesDir
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = AccountStore(files)
                    val latest = store.load().firstOrNull { it.id == account.id }
                        ?: throw IllegalStateException("${account.displayName} is no longer on this PC")
                    store.update(latest.copy(settings = settings))
                }
            }
            if (!isActive) return@launch
            saving = false
            result.onSuccess {
                host.toast("Settings saved")
                if (!closed) onSaved()
                host.refresh()
            }.onFailure {
                showErrors(PlacedErrors(emptyMap(), listOf("Could not save: ${it.message}")))
            }
        }
    }

    /** Shows [placed] under the boxes and at the top, opens their sections and scrolls to the first. */
    private fun showErrors(placed: PlacedErrors) {
        errors.clear()
        errors.putAll(placed.byField)
        topErrors = placed.top
        val first = if (placed.top.isNotEmpty()) TOP_ERRORS else BOX_ORDER.firstOrNull { it in placed.byField } ?: return
        placed.byField.keys.mapNotNull { FarmingSettingsEditor.sectionOf(it) }.toSet().forEach {
            if (it !in expanded) expanded += it
        }
        scrollTarget = first
    }

    companion object {
        private val CURRENCIES = listOf("auto", "gold", "elixir")

        /** Every box's key in screen order, so the error scrolled to is the highest one. */
        internal val BOX_ORDER: List<String> =
            listOf("attacks", "maxMinutes", "triggerPercent", "resumePercent", "maxWallLevel", "maxWallsPerPhase") +
                Resource.entries.map { "capacity_${it.key}" } +
                Resource.entries.map { "goal_${it.key}" } +
                Resource.entries.map { "minimum_${it.key}" } +
                listOf("minimumGoldAndElixir", "maxSkips", "maxSearchSeconds", "maxSearchGold",
                    "endDamageMin", "endDamageMax", "endAfterDeploySeconds", "endBattleSecondsLeft") +
                Resource.entries.map { "lootLeft_${it.key}" } +
                "savedArmy"
    }
}

/**
 * The editor sheet over the window: one collapsible card per section, each showing a one-line
 * summary while closed. [busy] is the tick's run flag; Save waits for the run to end, with a note
 * saying why. Show it by composing it; [onClose] must stop composing it.
 */
@Composable
fun AccountEditorSheet(model: AccountEditorModel, busy: Boolean, onClose: () -> Unit) {
    DisposableEffect(model) {
        onDispose { model.closed = true }
    }
    // A refused Save holds the note until the service is idle; the tick's flag covers the rest.
    LaunchedEffect(model.refusedBusy) {
        while (model.refusedBusy && FarmerService.instance?.isBusy == true) delay(1_000)
        model.refusedBusy = false
    }
    val runActive = busy || model.refusedBusy
    val fields = model.current()
    Sheet(
        title = "Edit ${model.account.displayName}",
        onClose = onClose,
        actions = {
            PrimaryButton("Save", enabled = AccountEditor.saveEnabled(runActive, model.saving), compact = true) {
                model.save(onClose)
            }
        }
    ) {
        if (runActive) {
            Label(BUSY_NOTE, Modifier.padding(top = 12.dp), size = FarmerTheme.SMALL, color = FarmerColors.warn)
        }
        if (model.topErrors.isNotEmpty()) {
            Label(model.topErrors.joinToString("\n"), Modifier.fillMaxWidth().errorAnchor(model, TOP_ERRORS).padding(top = 12.dp),
                size = FarmerTheme.SMALL, color = FarmerColors.bad)
        }
        Gap(8.dp)

        Section(model, SettingsSection.RUN_LIMITS, fields) {
            SettingBox(model, "attacks", "Attacks per run (1 to 100)")
            SettingBox(model, "maxMinutes", "Max minutes per run (1 to 120)")
        }
        Section(model, SettingsSection.WALLS, fields) {
            model.account.settings.wallSetupRequired?.let { reason ->
                Label("Walls were switched off when this account loaded: $reason " +
                    "Fix that, or switch Upgrade walls off to save the other settings without walls.",
                    Modifier.padding(top = 8.dp), size = FarmerTheme.SMALL, color = FarmerColors.warn)
            }
            ToggleRow("Upgrade walls", model.walls, subtitle = "Spend stored gold or elixir on walls between attacks.") {
                model.walls = it
            }
            SettingBox(model, "triggerPercent", "Start spending at, % of capacity (1 to 100)")
            SettingBox(model, "resumePercent", "Spend down to, % of capacity (below the start)")
            Caption(FarmingSettingsEditor.RESUME_NOTE)
            SettingBox(model, "maxWallLevel", "Target wall level (1 to 25)")
            Caption("Pay with")
            Segmented(listOf("Auto", "Gold", "Elixir"), model.currency, Modifier.padding(top = 6.dp)) { model.currency = it }
            SettingBox(model, "maxWallsPerPhase", "Max walls per phase (1 to 100)")
        }
        Section(model, SettingsSection.STORAGE_GOAL, fields) {
            Caption("Your storage limits. A goal needs the matching capacity and cannot exceed it.")
            Resource.entries.forEach { SettingBox(model, "capacity_${it.key}", "${FarmingSettingsEditor.resourceName(it)} capacity") }
            Caption("Stop farming once storage reaches these amounts. Blank means no goal for that resource.")
            Resource.entries.forEach { SettingBox(model, "goal_${it.key}", "${FarmingSettingsEditor.resourceName(it)} goal") }
            Caption("Stop when")
            Segmented(listOf("All reached", "Any reached"), model.goalMatch, Modifier.padding(top = 6.dp)) { model.goalMatch = it }
        }
        Section(model, SettingsSection.OPPONENTS, fields) {
            ToggleRow("Choose opponents by loot", model.opponents,
                subtitle = "Skip bases offering less, within the search limits. At a limit the current base is attacked.") {
                model.opponents = it
            }
            Resource.entries.forEach { SettingBox(model, "minimum_${it.key}", "${FarmingSettingsEditor.resourceName(it)} minimum") }
            SettingBox(model, "minimumGoldAndElixir", "Gold plus elixir minimum")
            SettingBox(model, "maxSkips", "Max skips per attack (0 to 50)")
            SettingBox(model, "maxSearchSeconds", "Max search time, seconds (1 to 300)")
            SettingBox(model, "maxSearchGold", "Max gold spent on Next per attack")
        }
        Section(model, SettingsSection.ENDING, fields) {
            ToggleRow("End battles early", model.earlyEnd,
                subtitle = "Surrender instead of waiting out the clock. A blank box turns its trigger off.") {
                model.earlyEnd = it
            }
            ToggleRow("At a damage picked for each battle", model.atDamage) { model.atDamage = it }
            SettingBox(model, "endDamageMin", "Lowest damage percent (1 to 100)")
            FarmingSettingsEditor.damageFloorHint(fields.endDamageMin)?.let { hint ->
                Label(hint, Modifier.padding(top = 8.dp), size = FarmerTheme.SMALL, color = FarmerColors.warn)
            }
            SettingBox(model, "endDamageMax", "Highest damage percent (1 to 100)")
            SettingBox(model, "endAfterDeploySeconds", "Seconds after the last troop lands (1 to 180)")
            SettingBox(model, "endBattleSecondsLeft", "When the battle clock shows this many seconds (1 to 179)")
            Resource.entries.forEach {
                SettingBox(model, "lootLeft_${it.key}", "${FarmingSettingsEditor.resourceName(it)} left on the base below")
            }
            Caption("Loot left fires once every amount filled in is under its value, and needs the scout loot rows calibrated.")
            ToggleRow("When the battle stalls", model.onStall,
                subtitle = "Experimental; stays off until the shadow logs are reviewed.") {
                model.onStall = it
            }
        }
        Section(model, SettingsSection.COLLECTION, fields) {
            ToggleRow("Collect before farming", model.collectBefore,
                subtitle = "Taps the collectors calibrated on the camera reference.") {
                model.collectBefore = it
            }
            ToggleRow("Collect after farming", model.collectAfter) { model.collectAfter = it }
        }
        Section(model, SettingsSection.ARMY, fields) {
            SettingBox(model, "savedArmy", "Saved army", hint = "Army 5")
            Caption("Before the first attack of a run, load this army from Saved Recipes, by the title on its card. " +
                "Leave blank to keep the current army.")
        }
    }
}

/** One collapsible card: a 56 dp header with the section title and summary over its fields. */
@Composable
private fun Section(
    model: AccountEditorModel,
    section: SettingsSection,
    fields: SettingsFields,
    content: @Composable ColumnScope.() -> Unit
) {
    val open = model.isExpanded(section)
    Card(padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverClick(shape = RoundedCornerShape(FarmerSize.CARD_RADIUS)) { model.toggle(section) }
                .semantics { contentDescription = "${section.title}, show or hide" }
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = FarmerSize.GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Label(section.title, size = FarmerTheme.BODY, bold = true)
                if (!open) {
                    Label(FarmingSettingsEditor.summaryOf(section, fields), Modifier.padding(top = 4.dp),
                        size = FarmerTheme.SMALL, color = FarmerColors.muted, maxLines = 1)
                }
            }
            Label(if (open) "Hide" else "Show", size = FarmerTheme.SMALL, color = FarmerColors.gold, medium = true)
        }
        if (open) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = FarmerSize.GUTTER, end = FarmerSize.GUTTER, bottom = FarmerSize.GUTTER),
                content = content
            )
        }
    }
}

/**
 * A labelled number box with its error line under it; with a [hint], a text box. A number box
 * takes only the digits typed or pasted, as the phone's number keyboard offered no other keys.
 */
@Composable
private fun SettingBox(model: AccountEditorModel, field: String, label: String, hint: String? = null) {
    val error = model.error(field)
    Column(Modifier.fillMaxWidth().errorAnchor(model, field).padding(top = 10.dp)) {
        Label(label, size = FarmerTheme.SMALL, color = FarmerColors.muted)
        TextInput(
            value = model.text(field),
            onValueChange = { next ->
                val value = if (hint == null) digitsTyped(model.text(field), next) else next
                if (value != model.text(field)) model.type(field, value)
            },
            modifier = Modifier.padding(top = 6.dp).semantics { contentDescription = label },
            hint = hint ?: "Blank",
            error = error != null
        )
        if (error != null) {
            Label(error, Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.bad)
        }
    }
}

/** A muted line between the boxes. */
@Composable
private fun Caption(text: String) {
    Label(text, Modifier.padding(top = 8.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted)
}

/**
 * Brings this box into view when it holds the first error a Save found. It waits one frame, so
 * a section the same Save opened has been laid out.
 */
@Composable
private fun Modifier.errorAnchor(model: AccountEditorModel, field: String): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val target = model.scrollTarget
    LaunchedEffect(target) {
        if (target == field) {
            withFrameNanos { }
            requester.bringIntoView()
            model.scrollTarget = null
        }
    }
    return this.bringIntoViewRequester(requester)
}

/**
 * [next] with only digits kept from what was typed or pasted into [old]. Text already in the box
 * stays as it was, as Android's number keyboard filtered only what it typed: a stored "2.5" still
 * shows, so Save can say why it is refused.
 */
internal fun digitsTyped(old: String, next: String): String {
    val shortest = minOf(old.length, next.length)
    var start = 0
    while (start < shortest && old[start] == next[start]) start++
    var end = 0
    while (end < shortest - start && old[old.length - 1 - end] == next[next.length - 1 - end]) end++
    val typed = next.substring(start, next.length - end)
    return next.substring(0, start) + typed.filter { it in '0'..'9' } + next.substring(next.length - end)
}
