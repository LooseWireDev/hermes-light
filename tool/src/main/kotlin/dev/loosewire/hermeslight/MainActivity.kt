package dev.loosewire.hermeslight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import dev.loosewire.hermeslight.data.FileProfileStorage
import dev.loosewire.hermeslight.data.KeystoreCipher
import dev.loosewire.hermeslight.data.ProfileStore
import dev.loosewire.hermeslight.net.Endpoint
import dev.loosewire.hermeslight.ui.AuthModeUi
import dev.loosewire.hermeslight.ui.BotRowUi
import dev.loosewire.hermeslight.ui.BotsActions
import dev.loosewire.hermeslight.ui.BotsScreen
import dev.loosewire.hermeslight.ui.BotsUi
import dev.loosewire.hermeslight.ui.HomeActions
import dev.loosewire.hermeslight.ui.HomeScreen
import dev.loosewire.hermeslight.ui.HomeUi
import dev.loosewire.hermeslight.ui.ListPosition
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The SDK owns the Android activity and generated manifest. */
@InitialScreen
class MainActivity(
    activity: SealedLightActivity,
) : SimpleLightScreen<Unit>(activity) {
    private val position = PositionCapture()
    private val controller by lazy {
        AppController(
            ProfileStore(FileProfileStorage(lightContext.filesDir), KeystoreCipher()),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    override fun willShow() {
        controller.resume()
        controller.refreshReadPosition()
    }

    override fun onAppPause() = controller.pause()

    override fun onScreenDestroy() = controller.close()

    @Composable
    override fun Content() = Surface(controller, Route.Home, position)
}

private enum class Route {
    Home,
    Sessions,
    Thread,
    Bots,
    Setup,
}

/** Kept by the SDK screen instance while a child covers its composition. */
private class PositionCapture(
    var list: ListPosition? = null,
)

private class DestinationScreen(
    activity: SealedLightActivity,
    private val controller: AppController,
    private val route: Route,
) : SimpleLightScreen<Unit>(activity) {
    private val position = PositionCapture()

    override fun willShow() {
        controller.resume()
        controller.refreshReadPosition()
    }

    override fun onAppPause() = controller.pause()

    @Composable
    override fun Content() = Surface(controller, route, position)
}

private fun SimpleLightScreen<Unit>.open(
    controller: AppController,
    route: Route,
) {
    if (route == Route.Bots) controller.loadBots()
    navigateTo({ DestinationScreen(it, controller, route) })
}

@Composable
private fun SimpleLightScreen<Unit>.Surface(
    controller: AppController,
    route: Route,
    position: PositionCapture,
) {
    val state by controller.state.collectAsState()
    val back: () -> Unit = { if (route != Route.Home) goBack() }
    val setup: () -> Unit = { if (route != Route.Setup) open(controller, Route.Setup) }
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
        )
    LightTheme(colors = LightThemeColors.Dark) {
        when {
            !state.initialized -> LightText("connecting…", LightTextVariant.Detail)
            route == Route.Setup || !state.configured -> SetupForm(state, controller, back)
            route == Route.Home ->
                HomeScreen(
                    HomeUi(thread = state.threadUi()),
                    HomeActions(
                        onOpenThread = {
                            if (state.selectedSession == null) {
                                open(controller, Route.Sessions)
                            } else {
                                open(controller, Route.Thread)
                            }
                        },
                        onOpenSessions = { open(controller, Route.Sessions) },
                        onBack = back,
                        onLongBack = setup,
                        threadActions = threadActions,
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
                        status = state.statusUi(),
                    ),
                    SessionsActions(
                        onOpenSession = {
                            controller.openSession(it)
                            open(controller, Route.Thread)
                        },
                        onOpenBots = { open(controller, Route.Bots) },
                        onLoadMore = { controller.loadSessions(reset = state.sessions.isEmpty()) },
                        onPositionChanged = { position.list = it },
                        onBack = back,
                        onLongBack = setup,
                    ),
                )
            route == Route.Thread -> ThreadScreen(state.threadUi(), threadActions)
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
                            open(controller, Route.Thread)
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
