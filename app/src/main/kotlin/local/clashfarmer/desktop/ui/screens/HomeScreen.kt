package local.clashfarmer.desktop.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.StorageReading
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.ui.DashboardHost
import local.clashfarmer.desktop.ui.DashboardScreen
import local.clashfarmer.desktop.ui.PcChecklist
import local.clashfarmer.desktop.ui.StartFix
import local.clashfarmer.desktop.ui.components.Card
import local.clashfarmer.desktop.ui.components.Chip
import local.clashfarmer.desktop.ui.components.DangerButton
import local.clashfarmer.desktop.ui.components.Dot
import local.clashfarmer.desktop.ui.components.FillBar
import local.clashfarmer.desktop.ui.components.Gap
import local.clashfarmer.desktop.ui.components.PAGE_MAX_WIDTH
import local.clashfarmer.desktop.ui.components.PrimaryButton
import local.clashfarmer.desktop.ui.components.ResourceBar
import local.clashfarmer.desktop.ui.components.ScreenPage
import local.clashfarmer.desktop.ui.components.SecondaryButton
import local.clashfarmer.desktop.ui.components.SectionHeader
import local.clashfarmer.desktop.ui.components.Sheet
import local.clashfarmer.desktop.ui.components.StartReason
import local.clashfarmer.desktop.ui.components.StatTileRow
import local.clashfarmer.desktop.ui.components.StickyFooter
import local.clashfarmer.desktop.ui.components.TextLink
import local.clashfarmer.desktop.ui.components.Tile
import local.clashfarmer.desktop.ui.components.ToneCard
import local.clashfarmer.desktop.ui.components.hoverClick
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.ink
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.insight.Format
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.service.FrameStore
import local.clashfarmer.ui.DashboardLoader
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.Tab
import local.clashfarmer.ui.components.AccountColor
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.ComposerState
import local.clashfarmer.ui.screens.HomeCards
import java.io.ByteArrayInputStream
import java.time.ZoneId
import javax.imageio.ImageIO

/**
 * Home (spec 2.2): what the bot is doing and whether it is fine. Idle, it shows the selected
 * account's storage, builders, loot rate and last raid, one attention card, and Start for the last
 * plan. Running, it shows the live frame, the phase, progress, and Pause beside Stop.
 */
class HomeScreen(private val host: DashboardHost) : DashboardScreen {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val loader = DashboardLoader(host.filesDir)
    private val scroll = ScrollState(0)

    private var state: DashboardState? by mutableStateOf(null)
    /** The time the page is drawn for: set on each bind, and once a minute while idle. */
    private var now: Long by mutableStateOf(System.currentTimeMillis())

    // Loaded off the UI thread, refreshed at most every 30 s (or when the account or log moves).
    private var extras: DashboardLoader.Home? by mutableStateOf(null)
    /** Every account's battles; null until the first read of them finishes. */
    private var records: List<BattleRecord>? by mutableStateOf(null)
    private var loadedAt = 0L
    private var loadedLastEvent: String? = null
    private var loading = false
    private var readingBattles = false
    /** Whether the page is on screen: a tick's load waits for it, as the phone's waited for the view. */
    private var visible = false

    /** [HomeCards.lastRunAccount], worked out again only when the log's last line, the run or the accounts move. */
    private var lastRunAccount: AccountRecord? by mutableStateOf(null)
    private var lastRunFrom: List<Any?>? = null

    // The live frame, decoded off the UI thread and only when its time changes.
    private var frameImage: ImageBitmap? by mutableStateOf(null)
    private var shownFrameAt = 0L
    private var decodingAt = 0L

    private var pickerOpen by mutableStateOf(false)

    override fun onShown() {
        state?.let { load(it, force = true) }
    }

    override fun bind(state: DashboardState) {
        this.state = state
        now = System.currentTimeMillis()
        // The log's last run may be the other account's: its line then says so, and the frame names its own.
        val snapshot = state.snapshot
        val from = listOf(state.events.lastOrNull(), snapshot.startedAt, snapshot.endedAt, snapshot.lastEventAt,
            snapshot.accountId, state.accounts)
        if (from != lastRunFrom) {
            lastRunAccount = HomeCards.lastRunAccount(snapshot, state.events, state.accounts)
            lastRunFrom = from
        }
        val frame = state.frame
        if (frame != null && framePill(state, now) != null) decode(frame)
        load(state, force = false)
    }

    @Composable
    override fun Content() {
        DisposableEffect(Unit) {
            visible = true
            onDispose { visible = false }
        }
        val state = state ?: return
        val busy = state.busy
        // While idle nothing rebinds on a clock: this ages the frame out and the tile chips along.
        LaunchedEffect(busy) {
            if (busy) return@LaunchedEffect
            while (true) {
                delay(CLOCK_TICK_MS)
                now = System.currentTimeMillis()
            }
        }
        val now = now
        val shown = state.shownAccount
        val data = extras?.takeIf { it.accountId == shown?.id }

        Column(Modifier.fillMaxSize()) {
            ScreenPage(Modifier.weight(1f), scroll) {
                Header(state)
                framePill(state, now)?.let { LiveFrame(it) }
                Status(state, now)

                Storage(shown, data?.storage, busy)
                StatTileRow(tiles(state, data, now))
                if (!busy) {
                    HomeCards.attention(shown, shown?.let { state.profiles[it.id] }, data?.markers.orEmpty(), data?.storage,
                        data?.builders, state.connected, busy, HomeCards.wallsOn(shown, data?.last, state.accounts, state.selectedId)
                    )?.let { Attention(it) }
                }

                val other = state.accounts.firstOrNull { it.id != state.shownAccountId }
                HomeCards.nextLine(other, state.runContext, busy, now)?.let { next ->
                    Card {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label(next, Modifier.weight(1f).padding(end = 10.dp), size = FarmerTheme.SMALL + 1, maxLines = 2)
                            if (!busy && other != null) {
                                SecondaryButton("Switch", compact = true, enabled = state.connected) { host.act("switch_only:${other.id}") }
                            }
                        }
                    }
                }
            }
            Actions(state, data?.last, now)
        }
        if (pickerOpen) AccountPicker(state)
    }

    /**
     * The account picker while idle, the account in the game while running, and a chip: the run's
     * state while one is going, else whether Clash's window is open. A window chip that needs the
     * user opens Setup, where its line says what to do.
     */
    @Composable
    private fun Header(state: DashboardState) {
        val window by host.windowStatus.collectAsState()
        val shown = state.shownAccount
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = FarmerSize.PRIMARY).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dot(shown?.let { Color(AccountColor.of(it.id)) } ?: FarmerColors.idle, 10.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.busy) {
                    Label(runningName(state), size = FarmerTheme.TITLE, bold = true, maxLines = 1)
                } else {
                    SecondaryButton(if (shown != null) "${shown.villageName}  ▾" else "Choose account  ▾",
                        enabled = state.accounts.isNotEmpty()) { pickerOpen = true }
                }
            }
            Spacer(Modifier.width(8.dp))
            val (word, tone) = when {
                state.busy && state.paused -> "Paused" to Tone.WARN
                state.busy -> "Running" to Tone.OK
                else -> gameChip(window)
            }
            val needsYou = !state.busy && (tone == Tone.WARN || tone == Tone.BAD)
            Chip(word, tone, if (needsYou) Modifier.hoverClick(shape = RoundedCornerShape(percent = 50)) {
                host.openTab(Tab.SETUP)
            } else Modifier, dot = true)
        }
    }

    /** "Blood (2 of 2)" in a combined run, else the account in the game. */
    private fun runningName(state: DashboardState): String {
        val name = state.runAccountName ?: "Running"
        if (state.runContext.combinedRunId == null) return name
        return "$name (${if (state.runContext.nextAccountName != null) 1 else 2} of 2)"
    }

    /**
     * The live frame's pill text and tone, or null to hide the frame. It shows while busy, and for
     * 10 minutes after when [HomeCards.lastSeen] can say whose it is.
     */
    private fun framePill(state: DashboardState, now: Long): Pair<String, Tone>? {
        val frame = state.frame ?: return null
        if (state.busy) {
            return if (state.paused) "Paused, frame ${Format.time(frame.atMs, zone)}" to Tone.WARN
            else "Live, ${Format.ago(frame.atMs, now)}" to Tone.OK
        }
        if (now - frame.atMs >= FRAME_LINGER_MS) return null
        val seen = HomeCards.lastSeen(frame.atMs, frame.accountId, state.accounts, state.shownAccountId, zone) ?: return null
        return seen to Tone.IDLE
    }

    /**
     * Decodes [frame] off the UI thread, unless it is the frame shown or the one being decoded. A
     * slow decode never replaces a newer frame.
     */
    private fun decode(frame: FrameStore.Snapshot) {
        if (frame.atMs == shownFrameAt || frame.atMs == decodingAt) return
        decodingAt = frame.atMs
        host.scope.launch {
            val image = withContext(Dispatchers.Default) {
                runCatching { ImageIO.read(ByteArrayInputStream(frame.bytes))?.toComposeImageBitmap() }.getOrNull()
            }
            if (image != null && frame.atMs > shownFrameAt) {
                frameImage = image
                shownFrameAt = frame.atMs
            }
        }
    }

    /**
     * The game window as the bot last saw it, the whole surface at its own shape. Before the
     * first frame the box takes the shape the window watcher last measured, so it does not jump
     * when the frame arrives. The pill sits bottom right, over Clash's Next or Shop button rather
     * than the name plate at top left.
     */
    @Composable
    private fun LiveFrame(pill: Pair<String, Tone>) {
        val image = frameImage
        val window by host.windowStatus.collectAsState()
        val ratio = image?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height }
            ?: window.surface?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height }
            ?: FALLBACK_RATIO
        val shape = RoundedCornerShape(FarmerSize.CARD_RADIUS)
        Box(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(shape)
                .background(FarmerColors.inset)
                .border(1.dp, FarmerColors.line, shape)
        ) {
            if (image != null) {
                Image(image, contentDescription = "The latest game frame", modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            }
            // The dark backing under its tinted fill keeps it as legible over a light frame as on the page.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(FarmerColors.withAlpha(FarmerColors.background, 0xE6))
            ) {
                Chip(pill.first, pill.second, dot = true)
            }
        }
    }

    /** The state word, the phase or last run, and while running the progress line and bar. */
    @Composable
    private fun Status(state: DashboardState, now: Long) {
        val busy = state.busy
        val snapshot = state.snapshot
        val shown = state.shownAccount
        Label(HomeCards.headline(snapshot, busy, state.paused), size = FarmerTheme.HEADLINE, bold = true, maxLines = 1)
        // Idle, a second line keeps the account and the attacks when the stop reason is long.
        Label(
            if (busy) snapshot.phase
            else HomeCards.lastRunLine(snapshot, zone, lastRunAccount?.takeIf { it.id != shown?.id }?.villageName),
            Modifier.padding(top = 6.dp),
            size = FarmerTheme.SMALL + 1,
            color = FarmerColors.muted,
            maxLines = if (busy) 1 else 2
        )
        if (busy) {
            Label(HomeCards.progressLine(snapshot, state.runContext, now, zone), Modifier.padding(top = 6.dp),
                size = FarmerTheme.SMALL + 1, maxLines = 1)
            val fraction = HomeCards.progress(snapshot, state.runContext)
            if (fraction != null) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FillBar(fraction, FarmerColors.gold, Modifier.weight(1f).height(6.dp))
                    Spacer(Modifier.width(10.dp))
                    Label("${(fraction * 100).toInt()}%", size = FarmerTheme.SMALL, color = FarmerColors.muted, medium = true)
                }
            }
        }
        Gap(16.dp)
    }

    /** Three bars when idle, one compact line while running. */
    @Composable
    private fun Storage(account: AccountRecord?, reading: StorageReading?, busy: Boolean) {
        val capacities = account?.settings?.capacities.orEmpty()
        val gold = reading?.values?.get(Resource.GOLD)
        val elixir = reading?.values?.get(Resource.ELIXIR)
        val dark = reading?.values?.get(Resource.DARK_ELIXIR)
        Card {
            SectionHeader(if (account != null && busy) "Storage (${account.villageName})" else "Storage",
                action = reading?.let { "read ${Format.time(it.atMs, zone)}" }, top = 0.dp)
            if (busy) {
                Label("Gold ${Format.ofCapacity(gold, capacities[Resource.GOLD])}  ·  " +
                    "Elixir ${Format.ofCapacity(elixir, capacities[Resource.ELIXIR])}", size = FarmerTheme.SMALL + 1, maxLines = 2)
            } else {
                ResourceBar(Resource.GOLD, gold, capacities[Resource.GOLD], Modifier.padding(top = 8.dp))
                ResourceBar(Resource.ELIXIR, elixir, capacities[Resource.ELIXIR], Modifier.padding(top = 8.dp))
                if (dark != null || capacities[Resource.DARK_ELIXIR] != null) {
                    ResourceBar(Resource.DARK_ELIXIR, dark, capacities[Resource.DARK_ELIXIR], Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    /** Builders, loot rate and last raid when idle; walls, spend and the run's loot rate while running. */
    private fun tiles(state: DashboardState, data: DashboardLoader.Home?, now: Long): List<Tile> {
        val id = state.shownAccountId
        val texts = if (state.busy) {
            val snapshot = state.snapshot
            val spent = snapshot.goldSpent + snapshot.elixirSpent
            listOf(
                HomeCards.TileText(snapshot.wallsUpgraded.toString(), "walls bought"),
                HomeCards.TileText(if (spent > 0) Format.amount(spent) else Format.UNKNOWN, "spent on walls"),
                HomeCards.runLootTile(records.orEmpty(), id, snapshot.startedAt)
            )
        } else {
            listOf(
                HomeCards.buildersTile(data?.builders, now),
                HomeCards.lootTile(records, id, now),
                HomeCards.lastRaidTile(records, id, now)
            )
        }
        return texts.map {
            Tile(it.value, it.label, it.chip, it.chipTone, valueTone = if (it.value == Format.UNKNOWN) Tone.IDLE else null)
        }
    }

    @Composable
    private fun Attention(attention: HomeCards.Attention) {
        ToneCard(attention.tone) {
            Label(attention.title, size = FarmerTheme.BODY, color = attention.tone.ink, bold = true)
            Label(attention.text, Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL + 1)
            val button = attention.button
            val action = attention.action
            if (button != null && action != null) {
                if (attention.tone == Tone.BAD) {
                    DangerButton(button, Modifier.padding(top = 12.dp), enabled = attention.enabled) { host.act(action) }
                } else {
                    SecondaryButton(button, Modifier.padding(top = 12.dp), enabled = attention.enabled) { host.act(action) }
                }
            }
        }
    }

    /**
     * Start (or why not, with a link to the fix) and Change run while idle; Pause or Resume beside
     * Stop while running. The supervised attack lives on Run, so its link opens Run.
     */
    @Composable
    private fun Actions(state: DashboardState, last: RunPlan?, now: Long) {
        StickyFooter(PAGE_MAX_WIDTH) {
            if (state.busy) {
                Row(Modifier.fillMaxWidth()) {
                    SecondaryButton(if (state.paused) "Resume" else "Pause", Modifier.weight(1f)) { host.act("pause") }
                    Spacer(Modifier.width(10.dp))
                    DangerButton("Stop", Modifier.weight(2f)) { host.act("stop") }
                }
            } else {
                val reason = last?.let {
                    ComposerState.readiness(it, state.accounts, state.profiles, state.connected, state.busy, state.selectedId,
                        now, zone).firstOrNull()
                }
                // Starts the plan the button names, so a reload landing between draw and click cannot change it.
                PrimaryButton(HomeCards.startLabel(last, state.accounts, state.selectedId), Modifier.fillMaxWidth(),
                    enabled = reason == null) {
                    if (last == null) host.openTab(Tab.RUN) else host.act("plan:${last.toJson()}")
                }
                StartFix.plainReason(reason)?.let {
                    val fix = last?.let { plan ->
                        StartFix.of(plan, state.accounts, state.profiles, state.selectedId, state.connected, state.busy, reason)
                    }
                    StartReason(it, fix?.label) {
                        when (fix) {
                            is StartFix.Calibrate -> host.openCalibration(fix.accountId)
                            is StartFix.SupervisedAttack -> host.openTab(Tab.RUN)
                            is StartFix.Open -> host.openTab(fix.tab)
                            null -> {}
                        }
                    }
                }
                if (last != null) TextLink("Change run >", Modifier.padding(top = 4.dp)) { host.openTab(Tab.RUN) }
            }
        }
    }

    /** One card per account: use it, or edit its settings. */
    @Composable
    private fun AccountPicker(state: DashboardState) {
        Sheet("Account", onClose = { pickerOpen = false }, width = 560.dp) {
            Gap(12.dp)
            state.accounts.forEach { account ->
                val selected = account.id == state.selectedId
                Card {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Dot(Color(AccountColor.of(account.id)), 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Label(account.villageName, Modifier.weight(1f), size = FarmerTheme.TITLE, bold = true, maxLines = 1)
                        if (selected) Chip("Selected", Tone.OK, dot = true)
                    }
                    Label(calibration(state.profiles[account.id]), Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL,
                        color = FarmerColors.muted)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        if (!selected) {
                            SecondaryButton("Use ${account.villageName}", Modifier.weight(1f)) {
                                host.act("select:${account.id}")
                                pickerOpen = false
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        SecondaryButton("Edit settings", Modifier.weight(1f)) {
                            pickerOpen = false
                            host.act("edit:${account.id}")
                        }
                    }
                }
            }
        }
    }

    private fun calibration(profile: PhoneProfile?): String {
        val attackError = profile?.let { runCatching { it.validateForAttack() }.exceptionOrNull()?.message }
        return when {
            profile == null -> "Not calibrated"
            attackError != null -> "Calibration incomplete"
            profile.isVerified -> "Calibrated and verified"
            else -> "Calibrated, not yet verified"
        }
    }

    /**
     * Reads the shown account's storage and builders, every wall marker and the last plan on IO and
     * shows them, then the battles, which can take seconds and once held the rest back. An account
     * change while the battles are read loads the rest at once; the battles are read once at a
     * time.
     */
    private fun load(state: DashboardState, force: Boolean) {
        if (!force && !visible) return
        val now = System.currentTimeMillis()
        val lastEvent = state.events.lastOrNull()
        val stale = now - loadedAt > CACHE_MS || state.shownAccountId != extras?.accountId ||
            (!state.busy && lastEvent != loadedLastEvent)
        if (loading || (!force && !stale)) return
        loading = true
        val accountId = state.shownAccountId
        val ids = state.accounts.map { it.id }
        host.scope.launch {
            val loaded = runCatching { withContext(Dispatchers.IO) { loader.home(accountId, ids) } }.getOrNull()
            loading = false
            // runCatching also catches cancellation; a closed window's screens must not be touched.
            if (!isActive) return@launch
            if (loaded != null) extras = loaded
            loadedAt = System.currentTimeMillis()
            loadedLastEvent = lastEvent
            this@HomeScreen.state?.let { bind(it) }
            if (readingBattles) return@launch
            readingBattles = true
            val battles = runCatching { withContext(Dispatchers.IO) { loader.battles() } }.getOrNull()
            readingBattles = false
            if (!isActive) return@launch
            if (battles != null) records = battles
        }
    }

    private companion object {
        const val CACHE_MS = 30_000L
        const val FRAME_LINGER_MS = 10 * 60_000L
        const val CLOCK_TICK_MS = 60_000L
        /**
         * The frame box's shape before the watcher has measured the game surface. The surface's
         * shape varies (16:9, or 16:10 such as 1860x1162 after a Google Play Games relaunch), so
         * this is only a placeholder; a frame or a measured surface replaces it.
         */
        const val FALLBACK_RATIO = 16f / 9f
    }
}

/**
 * Home's idle chip: whether Clash's window is open, in the tone Setup's game window line gives
 * it, so the two never disagree. Amber and red send the user to that line.
 */
internal fun gameChip(status: WindowStatus): Pair<String, Tone> {
    val tone = PcChecklist.gameWindow(status).tone
    val word = when {
        !status.checked -> "Looking for Clash"
        status.error != null -> "Clash not found"
        status.window == null -> "Clash not open"
        !status.window.visible -> "Clash closed"
        status.window.iconic -> "Clash minimized"
        else -> "Clash open"
    }
    return word to tone
}
