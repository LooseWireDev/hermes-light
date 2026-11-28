package dev.loosewire.hermeslight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.apps.LightAppList
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import dev.loosewire.hermeslight.data.FileProfileStorage
import dev.loosewire.hermeslight.data.KeystoreCipher
import dev.loosewire.hermeslight.data.ProfileStore
import dev.loosewire.hermeslight.hw.ThreadWheelViewModel
import dev.loosewire.hermeslight.hw.WheelDiagnostics
import dev.loosewire.hermeslight.net.Endpoint
import dev.loosewire.hermeslight.ui.AuthModeUi
import dev.loosewire.hermeslight.ui.BotRowUi
import dev.loosewire.hermeslight.ui.BotsActions
import dev.loosewire.hermeslight.ui.BotsScreen
import dev.loosewire.hermeslight.ui.BotsUi
import dev.loosewire.hermeslight.ui.ComposerEditor
import dev.loosewire.hermeslight.ui.HomeActions
import dev.loosewire.hermeslight.ui.HomeScreen
import dev.loosewire.hermeslight.ui.HomeUi
import dev.loosewire.hermeslight.ui.ListPosition
import dev.loosewire.hermeslight.ui.MenuActions
import dev.loosewire.hermeslight.ui.MenuScreen
import dev.loosewire.hermeslight.ui.MenuUi
import dev.loosewire.hermeslight.ui.ProviderUi
import dev.loosewire.hermeslight.ui.SessionsActions
import dev.loosewire.hermeslight.ui.SessionsScreen
import dev.loosewire.hermeslight.ui.SessionsUi
import dev.loosewire.hermeslight.ui.SetupActions
import dev.loosewire.hermeslight.ui.SetupScreen
import dev.loosewire.hermeslight.ui.SetupUi
import dev.loosewire.hermeslight.ui.ThreadActions
import dev.loosewire.hermeslight.ui.ThreadPosition
import dev.loosewire.hermeslight.ui.ThreadScreen
import dev.loosewire.hermeslight.ui.common.LocalScreenWheel
import dev.loosewire.hermeslight.voice.hasRecordAudioPermission
import dev.loosewire.hermeslight.voice.rememberRecordAudioPermissionLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The SDK owns the Android activity and generated manifest. */
@InitialScreen
class MainActivity(
    activity: SealedLightActivity,
) : LightScreen<Unit, ThreadWheelViewModel>(activity) {
    override val viewModelClass = ThreadWheelViewModel::class.java

    override fun createViewModel() = ThreadWheelViewModel()

    private val voice = VoiceAccessState()
    private val position = PositionCapture()
    private val controller by lazy {
        AppController(
            ProfileStore(FileProfileStorage(lightContext.filesDir), KeystoreCipher()),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    override fun willShow() {
        position.shownRevision++
        controller.resume()
        controller.refreshReadPosition()
    }

    override fun willHide() {
        voice.gesture++
        controller.cancelVoice()
    }

    override fun onAppPause() {
        voice.gesture++
        controller.pause()
    }

    override fun onScreenDestroy() = controller.close()

    @Composable
    override fun Content() = Surface(controller, Route.Home, position, viewModel, voice) { lightContext.appAccess.apps }
}

/** Kept by the SDK screen instance while a child covers its composition. */
private class PositionCapture(
    var list: ListPosition? = null,
) {
    var shownRevision by mutableIntStateOf(0)
}

/** Shared across the screen stack: a denied grant must not reopen the sheet on every hold. */
private class VoiceAccessState {
    var requested = false
    var gesture = 0
    var diagnostics by mutableStateOf<String?>(null)
}

private class DestinationScreen(
    activity: SealedLightActivity,
    private val controller: AppController,
    private val route: Route,
    private val voice: VoiceAccessState,
) : LightScreen<Unit, ThreadWheelViewModel>(activity) {
    override val viewModelClass = ThreadWheelViewModel::class.java

    override fun createViewModel() = ThreadWheelViewModel()

    private val position = PositionCapture()

    override fun willShow() {
        position.shownRevision++
        controller.resume()
        controller.refreshReadPosition()
        if (route == Route.Menu) controller.menuShown()
    }

    override fun willHide() {
        voice.gesture++
        controller.cancelVoice()
        if (route == Route.Menu) controller.menuHidden()
    }

    override fun onAppPause() {
        voice.gesture++
        controller.pause()
    }

    @Composable
    override fun Content() = Surface(controller, route, position, viewModel, voice) { lightContext.appAccess.apps }
}

private fun SimpleLightScreen<Unit>.open(
    controller: AppController,
    from: Route,
    route: Route,
    voice: VoiceAccessState,
) {
    route.openFrom(
        from = from,
        back = { goBack() },
        forward = { destination ->
            if (destination == Route.Bots) controller.loadBots()
            navigateTo({ DestinationScreen(it, controller, destination, voice) })
        },
    )
}

@Composable
private fun SimpleLightScreen<Unit>.Surface(
    controller: AppController,
    route: Route,
    position: PositionCapture,
    wheel: ThreadWheelViewModel,
    voice: VoiceAccessState,
    appList: () -> LightAppList,
) {
    val state by controller.state.collectAsState()
    val back: () -> Unit = { if (route != Route.Home) goBack() }
    val setup: () -> Unit = { if (route != Route.Setup) open(controller, route, Route.Setup, voice) }
    var composing by remember(state.selectedSession, state.profileVersion) { mutableStateOf(false) }
    var composerFocusRevision by remember(state.selectedSession, state.profileVersion) { mutableIntStateOf(0) }
    val voiceLevel by controller.voiceLevel.collectAsState()
    val voicePartial by controller.voicePartial.collectAsState()
    val permissionLauncher = rememberRecordAudioPermissionLauncher()
    val scope = rememberCoroutineScope()
    DisposableEffect(state.selectedSession, state.profileVersion) {
        onDispose {
            voice.gesture++
            controller.cancelVoice()
        }
    }
    val thread =
        remember(state) { state.threadUi() }.copy(
            wheelDiagnostics = voice.diagnostics,
            voiceLevel = voiceLevel,
            voicePartial = voicePartial,
            composerFocusRevision = composerFocusRevision,
        )
    LaunchedEffect(state.sessionsRequired) {
        if (state.sessionsRequired) {
            controller.sessionsShown()
            open(controller, route, Route.Sessions, voice)
        }
    }
    val threadActions =
        ThreadActions(
            onLoadOlder = controller::loadOlder,
            onLoadNewer = controller::loadNewer,
            onPositionChanged = { point: ThreadPosition ->
                val row = point.messageId.substringBefore(':').toIntOrNull()
                if (row != null) state.selectedSession?.let { controller.rememberPosition(it, row, point.offsetPx) }
            },
            onBack = back,
            onLongBack = setup,
            onDraftChange = { text -> state.selectedSession?.let { controller.editDraft(it, text) } },
            onSubmit = { text ->
                state.selectedSession?.let {
                    controller.editDraft(it, text)
                    controller.submit(it, text)
                }
            },
            onAction = { action ->
                if (action == "compose") {
                    composing = true
                } else if (route == Route.Home && (action.startsWith("approval:") || action.startsWith("clarify:"))) {
                    open(controller, route, Route.Thread, voice)
                } else {
                    state.selectedSession?.let {
                        controller.activate(it, action) {
                            if (route != Route.Thread) open(controller, route, Route.Thread, voice)
                        }
                    }
                }
            },
            onHoldStart = {
                val gesture = ++voice.gesture
                scope.launch {
                    val granted = hasRecordAudioPermission()
                    if (gesture != voice.gesture) return@launch
                    if (!granted) {
                        controller.voicePermissionDenied()
                        if (!voice.requested && permissionLauncher != null) {
                            voice.requested = true
                            permissionLauncher.launch()
                        }
                    } else {
                        controller.startVoice()
                    }
                }
            },
            onHoldEnd = {
                voice.gesture++
                controller.releaseVoice()
            },
            onHoldCancel = {
                voice.gesture++
                controller.cancelVoice()
            },
            onDiagnostics = diagnostics@{
                if (voice.diagnostics != null) {
                    voice.diagnostics = null
                    return@diagnostics
                }
                scope.launch {
                    val granted = hasRecordAudioPermission()
                    val installed =
                        try {
                            if (appList().installed().any { it.packageName == "com.gios.lightcontrol" }) "yes" else "no"
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            "unavailable"
                        }
                    val counters = WheelDiagnostics.snapshot()
                    voice.diagnostics =
                        "clicks: ${counters.clicks} · turns: ${counters.turns}\n" +
                        "mic granted: ${if (granted) "yes" else "no"} · BrightControl installed: $installed" +
                        if (counters.clicks == 0) {
                            "\nFix: BrightControl → dev.loosewire.hermeslight → Off / hands-off"
                        } else {
                            ""
                        }
                }
            },
        )
    LightTheme(colors = LightThemeColors.Dark) {
        CompositionLocalProvider(LocalScreenWheel provides wheel) {
            when {
                !state.initialized -> LightText("connecting…", LightTextVariant.Detail)
                composing ->
                    ComposerEditor(thread, threadActions) {
                        composerFocusRevision++
                        composing = false
                    }
                route == Route.Setup || !state.configured -> SetupForm(state, controller, back)
                route == Route.Home ->
                    HomeScreen(
                        HomeUi(statusStrip = state.statusStripUi(), thread = thread),
                        HomeActions(
                            onOpenThread = {
                                if (state.selectedSession == null) {
                                    open(controller, route, Route.Sessions, voice)
                                } else {
                                    open(controller, route, Route.Thread, voice)
                                }
                            },
                            onOpenStatus = { session ->
                                if (session != null) controller.openSession(session)
                                open(controller, route, Route.Thread, voice)
                            },
                            onOpenMenu = { open(controller, route, Route.Menu, voice) },
                            onBack = back,
                            onLongBack = setup,
                            threadActions = threadActions,
                        ),
                    )
                route == Route.Menu ->
                    MenuScreen(
                        MenuUi(
                            status = state.statusUi(),
                            model =
                                state.sessions
                                    .firstOrNull { it.id == state.selectedSession }
                                    ?.model
                                    ?.takeIf(String::isNotBlank)
                                    ?: state.menuSessions
                                        .firstOrNull { it.id == state.selectedSession }
                                        ?.model
                                        ?.takeIf(String::isNotBlank)
                                    ?: "",
                            sessionCount = state.menuSessionCount,
                            botCount = state.bots.size.takeIf { state.botsLoaded },
                            cronCount = state.menuCronCount,
                            cronSummary = state.menuCronSummary,
                            recentSessions = state.sessionRows(System.currentTimeMillis(), state.menuSessions),
                            error = state.menuError,
                        ),
                        MenuActions(
                            onHistory = {
                                if (state.selectedBot != null) controller.selectBot(null)
                                open(controller, route, Route.Sessions, voice)
                            },
                            onBots = { open(controller, route, Route.Bots, voice) },
                            onSettings = setup,
                            onOpenSession = {
                                if (state.selectedBot != null) controller.selectBot(null)
                                controller.openSession(it)
                                open(controller, route, Route.Thread, voice)
                            },
                            onBack = back,
                        ),
                    )
                route == Route.Sessions ->
                    SessionsScreen(
                        SessionsUi(
                            sessions = state.sessionRows(System.currentTimeMillis()),
                            profileId = state.selectedBot,
                            selectedSessionId = state.selectedSession,
                            position = position.list,
                            hasMore = state.sessionsHaveMore,
                            loading = state.sessionsLoading,
                            loadError = state.operationError,
                            creating = state.creatingSession,
                            status = state.statusUi(),
                        ),
                        SessionsActions(
                            onOpenSession = {
                                controller.openSession(it)
                                open(controller, route, Route.Thread, voice)
                            },
                            onNewSession = {
                                controller.createSession {
                                    open(controller, route, Route.Thread, voice)
                                }
                            },
                            onOpenBots = { open(controller, route, Route.Bots, voice) },
                            onLoadMore = { controller.loadSessions(reset = state.sessions.isEmpty()) },
                            onPositionChanged = { position.list = it },
                            onBack = back,
                            onLongBack = setup,
                        ),
                    )
                route == Route.Thread -> ThreadScreen(thread, threadActions)
                route == Route.Bots ->
                    BotsScreen(
                        BotsUi(
                            bots =
                                listOf(BotRowUi("", "All sessions", "all")) +
                                    state.bots.map {
                                        BotRowUi(it.id, it.name, it.source ?: "profile", it.count, it.profile)
                                    },
                            selectedBotId = state.selectedBot,
                            position = position.list,
                            loading = state.botsLoading,
                            loadError = state.operationError,
                            status = state.statusUi(),
                        ),
                        BotsActions(
                            onOpenBot = {
                                controller.selectBot(it.takeIf(String::isNotEmpty))
                                open(controller, route, Route.Sessions, voice)
                            },
                            onLoadBots = controller::loadBots,
                            onPositionChanged = { position.list = it },
                            onBack = back,
                            onLongBack = setup,
                        ),
                    )
            }
        }
    }
}

@Composable
private fun SetupForm(
    state: AppState,
    controller: AppController,
    back: () -> Unit,
) {
    var endpoint by remember { mutableStateOf(state.endpoint) }
    var username by remember { mutableStateOf(state.username) }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(if (state.passwordMode) AuthModeUi.Password else AuthModeUi.Token) }
    val initialVersion = remember { state.profileVersion }
    LaunchedEffect(state.profileVersion) {
        if (state.profileVersion > initialVersion) back()
    }
    val warning = Endpoint.parseOrNull(endpoint)?.cleartextWarning
    val providers = state.authProviders.map { ProviderUi(it.name, it.displayName, it.supportsPassword) }
    SetupScreen(
        SetupUi(
            endpoint = endpoint,
            username = username,
            mode = mode,
            token = token,
            password = password,
            providers = providers,
            providerStatus =
                listOfNotNull(
                    providers.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Providers: ") { it.label },
                    warning,
                ).joinToString("\n"),
            connection = state.statusUi(),
            testing = state.connecting,
            error = state.status.takeIf { it.startsWith("error:") } ?: state.operationError,
        ),
        SetupActions(
            onEndpointChange = { endpoint = it },
            onUsernameChange = { username = it },
            onModeChange = { mode = it },
            onTokenChange = { token = it },
            onPasswordChange = { password = it },
            onTestConnection = { controller.connect(endpoint, password, token, username, mode == AuthModeUi.Password) },
            onBack = back,
        ),
    )
}
