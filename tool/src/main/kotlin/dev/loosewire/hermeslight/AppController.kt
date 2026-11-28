package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.AuthMode
import dev.loosewire.hermeslight.data.Profile
import dev.loosewire.hermeslight.data.ProfileStore
import dev.loosewire.hermeslight.data.SessionPage
import dev.loosewire.hermeslight.data.SessionSummary
import dev.loosewire.hermeslight.data.TranscriptMessage
import dev.loosewire.hermeslight.net.Api
import dev.loosewire.hermeslight.net.ApiException
import dev.loosewire.hermeslight.net.Auth
import dev.loosewire.hermeslight.net.AuthProvider
import dev.loosewire.hermeslight.net.Endpoint
import dev.loosewire.hermeslight.net.Gateway
import dev.loosewire.hermeslight.net.GatewayConnection
import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.net.GatewayRpcException
import dev.loosewire.hermeslight.net.GatewayState
import dev.loosewire.hermeslight.net.ProbeResult
import dev.loosewire.hermeslight.net.RpcFailureKind
import dev.loosewire.hermeslight.net.SessionRuntime
import dev.loosewire.hermeslight.net.SignInResult
import dev.loosewire.hermeslight.net.TicketResult
import dev.loosewire.hermeslight.net.WsAuthParam
import dev.loosewire.hermeslight.net.defaultHttpClient
import dev.loosewire.hermeslight.ui.DEFAULT_ENDPOINT
import dev.loosewire.hermeslight.ui.SessionActionUi
import dev.loosewire.hermeslight.voice.Listener
import dev.loosewire.hermeslight.voice.WavAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class BotSource(
    val id: String,
    val name: String,
    val source: String?,
    val profile: String?,
    val count: Int,
)

internal data class AppState(
    val initialized: Boolean = false,
    val configured: Boolean = false,
    val profileVersion: Int = 0,
    val endpoint: String = DEFAULT_ENDPOINT,
    val status: String = "connecting…",
    val operationError: String? = null,
    val connecting: Boolean = false,
    val authProviders: List<AuthProvider> = emptyList(),
    val passwordMode: Boolean = false,
    val username: String = "",
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsHaveMore: Boolean = false,
    val sessionsQuery: String = "",
    val sessionsSearch: SessionPage? = null,
    val sessionsSearchLoading: Boolean = false,
    val historyAction: SessionActionUi? = null,
    val historyBusy: Boolean = false,
    val historyError: String? = null,
    val bots: List<BotSource> = emptyList(),
    val menuSessions: List<SessionSummary> = emptyList(),
    val menuSessionCount: Int? = null,
    val menuCronCount: Int? = null,
    val menuCronSummary: String = "Loading jobs…",
    val menuError: String? = null,
    val botsLoaded: Boolean = false,
    val botsLoading: Boolean = false,
    val selectedBot: String? = null,
    val selectedSession: String? = null,
    val createdSession: String? = null,
    val creatingSession: Boolean = false,
    val messages: List<TranscriptMessage> = emptyList(),
    val threadLoading: Boolean = false,
    val threadHasOlder: Boolean = false,
    val threadHasNewer: Boolean = false,
    val positionRow: Int? = null,
    val positionOffset: Int = 0,
    val liveSessions: Set<String> = emptySet(),
    val desktopNotices: Map<String, List<DesktopNotice>> = emptyMap(),
    val conversations: Map<String, Conversation> = emptyMap(),
    val sessionsRequired: Boolean = false,
    val voiceFeedback: String? = null,
    val voiceState: Listener.State = Listener.State.Idle,
)

/** One foreground connection owner shared by the SDK screen stack. No network work in Compose. */
internal class AppController(
    private val store: ProfileStore,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()

    @Volatile
    private var profile: Profile? = null
    private var foreground = false
    private var connectionJob: Job? = null
    private var sessionsJob: Job? = null
    private var threadJob: Job? = null
    private var creationJob: Job? = null
    private var botsJob: Job? = null
    private var menuJob: Job? = null
    private var menuVisible = false
    private var positionSaveJob: Job? = null
    private var sessionsOffset = 0
    private var connectionEpoch = 0
    private var historyJob: Job? = null
    private var sessionsSearchJob: Job? = null
    private var historyEpoch = 0
    private var sessionListRevision = 0L
    private val client = defaultHttpClient()
    private var nextPromptId = 0L
    private val eventRevisions = mutableMapOf<String, Long>()
    private val activityRevisions = mutableMapOf<String, Long>()
    private val queuedChecks = mutableMapOf<String, Job>()
    private val refreshJobs = mutableMapOf<String, Job>()
    private var attentionJob: Job? = null
    private val api = Api(client, tokenProvider = { profile?.takeIf { it.authMode == AuthMode.TOKEN }?.token })
    private val history =
        SessionManagement(
            state = { mutable.value },
            update = { transform -> mutable.update(transform) },
            rename = { id, title ->
                val saved = profile ?: error("Not connected to the dashboard")
                api.renameSession(Endpoint.parse(saved.endpoint), id, title)
            },
            delete = { id ->
                val saved = profile ?: error("Not connected to the dashboard")
                api.deleteSession(Endpoint.parse(saved.endpoint), id)
            },
            searchRequest = { query ->
                val saved = profile ?: error("Not connected to the dashboard")
                val bot = mutable.value.selectedBot
                api.searchSessions(
                    endpoint = Endpoint.parse(saved.endpoint),
                    query = query,
                    profile = bot?.takeIf { it.startsWith("profile:") }?.removePrefix("profile:"),
                    source = bot?.takeIf { it.startsWith("source:") }?.removePrefix("source:"),
                )
            },
            onChanged = { sessionListRevision += 1 },
            onDeleted = ::sessionDeleted,
            current = { historyEpoch == connectionEpoch },
        )
    private var sessionRuntime: SessionRuntime? = null
    private val auth = Auth(client)
    private val saveMutex = Mutex()
    private var handshake: CompletableDeferred<Unit>? = null
    private var voice: Listener? = null
    private var voiceTelemetry: Job? = null
    private val mutableVoiceLevel = MutableStateFlow(0f)
    val voiceLevel = mutableVoiceLevel.asStateFlow()
    private val mutableVoicePartial = MutableStateFlow("")
    val voicePartial = mutableVoicePartial.asStateFlow()
    private val gateway =
        Gateway(
            scope,
            object : Gateway.Listener {
                override fun onState(state: GatewayState) {
                    mutable.update { it.copy(status = connectionLabel(state)) }
                    when (state) {
                        GatewayState.Connected -> {
                            if (handshake != null) {
                                handshake?.complete(Unit)
                            } else {
                                mutable.value.selectedSession?.let { openSession(it, remembered = true) }
                                resumeQueuedChecks()
                                refreshAttention()
                            }
                        }
                        is GatewayState.AuthFailed -> handshake?.completeExceptionally(IllegalStateException(state.message))
                        else -> Unit
                    }
                }

                override fun onEvent(event: GatewayEvent) {
                    receive(event)
                }
            },
            client,
        )

    fun resume() {
        if (foreground) return
        foreground = true
        if (!mutable.value.initialized) {
            connectionJob =
                scope.launch {
                    try {
                        profile = withContext(Dispatchers.IO) { store.load() }
                        val saved = profile
                        mutable.update {
                            it.copy(
                                initialized = true,
                                configured = saved != null,
                                endpoint = saved?.endpoint ?: DEFAULT_ENDPOINT,
                                selectedSession = saved?.selectedSessionId,
                                selectedBot = saved?.botProfile,
                                passwordMode = saved?.authMode == AuthMode.PASSWORD,
                                username = saved?.username.orEmpty(),
                            )
                        }
                        if (saved == null) {
                            mutable.update { it.copy(status = "not connected — set up dashboard") }
                        } else {
                            connectProfile(saved)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        mutable.update {
                            it.copy(
                                initialized = true,
                                status = "error: unable to read encrypted profile",
                                operationError = "Unable to read encrypted profile; reconnect in Setup",
                            )
                        }
                    }
                }
        } else {
            profile?.let { saved ->
                connectionJob = scope.launch { connectProfile(saved) }
            }
        }
    }

    fun pause() {
        cancelVoice()
        foreground = false
        connectionJob?.cancel()
        sessionsJob?.cancel()
        threadJob?.cancel()
        creationJob?.cancel()
        botsJob?.cancel()
        menuJob?.cancel()
        historyJob?.cancel()
        sessionsSearchJob?.cancel()
        history.invalidate()
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
        queuedChecks.values.forEach { it.cancel() }
        queuedChecks.clear()
        attentionJob?.cancel()
        gateway.onForeground(false)
        mutable.update {
            it.copy(
                connecting = false,
                sessionsLoading = false,
                sessionsSearchLoading = false,
                threadLoading = false,
                botsLoading = false,
                historyAction = null,
                historyBusy = false,
                historyError = null,
            )
        }
    }

    fun close() {
        pause()
        gateway.disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }

    fun connect(
        rawEndpoint: String,
        password: String,
        token: String,
        username: String,
        passwordMode: Boolean,
    ) {
        cancelVoice()
        connectionJob?.cancel()
        sessionsJob?.cancel()
        threadJob?.cancel()
        creationJob?.cancel()
        botsJob?.cancel()
        menuJob?.cancel()
        historyJob?.cancel()
        sessionsSearchJob?.cancel()
        history.invalidate()
        mutable.update {
            it.copy(
                sessionsLoading = false,
                threadLoading = false,
                botsLoading = false,
                sessionsQuery = "",
                sessionsSearch = null,
                sessionsSearchLoading = false,
                historyAction = null,
                historyBusy = false,
                historyError = null,
            )
        }
        connectionJob =
            scope.launch {
                val candidate =
                    try {
                        val endpoint = Endpoint.parse(rawEndpoint).baseUrl
                        val previous = profile?.takeIf { it.endpoint == endpoint } ?: Profile(endpoint)
                        previous.copy(
                            authMode = if (passwordMode) AuthMode.PASSWORD else AuthMode.TOKEN,
                            token = token.trim().takeIf { !passwordMode && it.isNotEmpty() },
                            username = username.trim().takeIf { passwordMode && it.isNotEmpty() },
                            password = password.takeIf { passwordMode && it.isNotEmpty() },
                        )
                    } catch (error: IllegalArgumentException) {
                        mutable.update { it.copy(status = "error: ${error.message}", connecting = false) }
                        return@launch
                    }
                connectProfile(candidate, persist = true)
            }
    }

    private suspend fun connectProfile(
        candidate: Profile,
        persist: Boolean = false,
    ) {
        val epoch = ++connectionEpoch
        historyJob?.cancel()
        sessionsSearchJob?.cancel()
        history.invalidate()
        queuedChecks.values.forEach { it.cancel() }
        queuedChecks.clear()
        gateway.disconnect()
        sessionRuntime = null
        mutable.update {
            it.copy(
                connecting = true,
                status = "connecting…",
                operationError = null,
                sessionsSearch = null,
                sessionsSearchLoading = false,
            )
        }
        try {
            withTimeout(25_000) {
                val endpoint = Endpoint.parse(candidate.endpoint)
                var authenticated = candidate
                val probe = auth.probe(endpoint)
                mutable.update {
                    it.copy(
                        authProviders = (probe as? ProbeResult.Gated)?.providers.orEmpty(),
                        passwordMode = probe is ProbeResult.Gated,
                    )
                }
                val credential =
                    when (probe) {
                        is ProbeResult.Failed -> error(probe.error.message)
                        is ProbeResult.Open -> {
                            val token = if (persist) candidate.token ?: probe.token else probe.token ?: candidate.token
                            if (token.isNullOrBlank()) error("Dashboard did not provide a token; paste one in Setup")
                            authenticated =
                                candidate.copy(
                                    authMode = AuthMode.TOKEN,
                                    token = token,
                                    password = null,
                                    username = null,
                                    provider = null,
                                )
                            WsAuthParam.TOKEN to token
                        }
                        is ProbeResult.Gated -> {
                            if (candidate.authMode != AuthMode.PASSWORD || candidate.password.isNullOrEmpty()) {
                                error("Password required — select Sign in in Setup")
                            }
                            val provider =
                                probe.providers.firstOrNull { it.supportsPassword }?.name
                                    ?: error("Dashboard has no supported password sign-in provider")
                            val login = auth.signIn(endpoint, candidate.username.orEmpty(), candidate.password, provider)
                            authenticated = candidate.copy(authMode = AuthMode.PASSWORD, token = null, provider = provider)
                            when (login) {
                                is SignInResult.Failed -> error(login.error.message)
                                is SignInResult.Success -> WsAuthParam.TICKET to login.ticket
                            }
                        }
                    }
                handshake = CompletableDeferred()
                var firstTicket: String? = credential.second
                gateway.onForeground(true)
                gateway.connect(
                    GatewayConnection(endpoint, credential.first, credential.second) {
                        if (credential.first == WsAuthParam.TOKEN) {
                            credential.second
                        } else {
                            val first = firstTicket
                            if (first != null) {
                                firstTicket = null
                                first
                            } else {
                                when (val ticket = auth.mintWsTicket(endpoint)) {
                                    is TicketResult.Success -> ticket.ticket
                                    is TicketResult.Failed -> error(ticket.error.message)
                                }
                            }
                        }
                    },
                )
                handshake?.await()
                if (persist) withContext(Dispatchers.IO) { saveMutex.withLock { store.save(authenticated) } }
                profile = authenticated
                sessionRuntime =
                    SessionRuntime(
                        resolve = { session ->
                            val id = api.latestDescendant(endpoint, session, selectedProfile(authenticated))
                            check(epoch == connectionEpoch) { "Connection changed before the prompt was sent" }
                            id
                        },
                        resumeSession = { tip ->
                            check(epoch == connectionEpoch) { "Connection changed before the session was resumed" }
                            val id = gateway.resumeSession(tip, selectedProfile(authenticated))
                            check(epoch == connectionEpoch) { "Connection changed while the session was resuming" }
                            id
                        },
                        request = { params ->
                            check(epoch == connectionEpoch) { "Connection changed before the prompt was sent" }
                            gateway.request("prompt.submit", params)
                        },
                    )
                mutable.update {
                    it.copy(
                        configured = true,
                        profileVersion = it.profileVersion + if (persist) 1 else 0,
                        endpoint = authenticated.endpoint,
                        selectedBot = authenticated.botProfile,
                        selectedSession = authenticated.selectedSessionId,
                        sessionsQuery = "",
                        sessionsSearch = null,
                        sessionsSearchLoading = false,
                        historyAction = null,
                        historyBusy = false,
                        historyError = null,
                        menuSessions = if (persist) emptyList() else it.menuSessions,
                        menuSessionCount = if (persist) null else it.menuSessionCount,
                        menuCronCount = if (persist) null else it.menuCronCount,
                        menuCronSummary = if (persist) "Loading jobs…" else it.menuCronSummary,
                        menuError = null,
                        botsLoaded = if (persist) false else it.botsLoaded,
                        sessions = if (persist) emptyList() else it.sessions,
                        messages = if (persist) emptyList() else it.messages,
                        desktopNotices = if (persist) emptyMap() else it.desktopNotices,
                        liveSessions = if (persist) emptySet() else it.liveSessions,
                        conversations = if (persist) emptyMap() else it.conversations,
                        sessionsRequired = false,
                    )
                }
                loadSessions(reset = true)
                refreshAttention()
                if (menuVisible) {
                    loadMenu()
                    loadBots()
                }
                authenticated.selectedSessionId?.let { openSession(it, remembered = true) }
                resumeQueuedChecks()
            }
        } catch (error: CancellationException) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) {
                gateway.disconnect()
                mutable.update { it.copy(status = "error: dashboard unreachable — connection timed out within 25 seconds") }
            } else {
                throw error
            }
        } catch (error: Exception) {
            gateway.disconnect()
            mutable.update { it.copy(status = "error: ${error.message ?: "dashboard unreachable — check address"}") }
        } finally {
            if (epoch == connectionEpoch) {
                handshake = null
                mutable.update { it.copy(connecting = false) }
            }
        }
    }

    fun menuShown() {
        menuVisible = true
        loadMenu()
        loadBots()
    }

    fun menuHidden() {
        menuVisible = false
    }

    private fun loadMenu() {
        val saved = profile ?: return
        val epoch = connectionEpoch
        val revision = sessionListRevision
        menuJob?.cancel()
        menuJob =
            scope.launch {
                mutable.update { it.copy(menuError = null) }
                val endpoint = Endpoint.parse(saved.endpoint)
                try {
                    val page = api.sessions(endpoint, limit = 3)
                    if (epoch != connectionEpoch || revision != sessionListRevision) return@launch
                    mutable.update {
                        it.copy(
                            menuSessions = page.sessions,
                            menuSessionCount = page.total.takeIf { total -> total > 0 || page.sessions.isEmpty() },
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (epoch == connectionEpoch && revision == sessionListRevision) {
                        mutable.update {
                            it.copy(
                                menuSessions = emptyList(),
                                menuSessionCount = null,
                                menuError = error.message ?: "Sessions unavailable",
                            )
                        }
                    }
                }
                try {
                    val jobs = api.cronJobs(endpoint, selectedProfile(saved))
                    if (epoch != connectionEpoch || revision != sessionListRevision) return@launch
                    mutable.update {
                        it.copy(
                            menuCronCount = jobs.size,
                            menuCronSummary =
                                if (jobs.isEmpty()) {
                                    "No scheduled jobs. Create and manage jobs on the desktop."
                                } else {
                                    jobs.joinToString("\n") { job ->
                                        job.name + (job.state?.let { state -> " · $state" } ?: "")
                                    }
                                },
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (epoch == connectionEpoch && revision == sessionListRevision) {
                        mutable.update { it.copy(menuCronCount = null, menuCronSummary = error.message ?: "Jobs unavailable") }
                    }
                }
            }
    }

    fun loadSessions(reset: Boolean = false) {
        val saved = profile ?: return
        if (mutable.value.sessionsLoading && !reset) return
        sessionsJob?.cancel()
        val bot = mutable.value.selectedBot
        val epoch = connectionEpoch
        val revision = sessionListRevision
        val source = bot?.takeIf { it.startsWith("source:") }?.removePrefix("source:")
        val botProfile = bot?.takeIf { it.startsWith("profile:") }?.removePrefix("profile:")
        if (reset) sessionsOffset = 0
        val offset = sessionsOffset
        mutable.update {
            it.copy(
                sessionsLoading = true,
                sessionsHaveMore = if (reset) true else it.sessionsHaveMore,
                operationError = null,
            )
        }
        sessionsJob =
            scope.launch {
                try {
                    val page =
                        api.sessions(
                            Endpoint.parse(saved.endpoint),
                            offset = offset,
                            source = source,
                            profile = botProfile,
                        )
                    if (epoch != connectionEpoch || revision != sessionListRevision || mutable.value.selectedBot != bot) return@launch
                    sessionsOffset = offset + page.sessions.size
                    mutable.update {
                        it.copy(
                            sessions = ((if (reset) emptyList() else it.sessions) + page.sessions).distinctBy { row -> row.id },
                            sessionsHaveMore = page.hasMore,
                        )
                    }
                    if (mutable.value.selectedSession == null && !mutable.value.sessionsRequired) {
                        page.sessions.firstOrNull()?.let { openSession(it.id) }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (epoch == connectionEpoch && revision == sessionListRevision) operationFailure(error)
                } finally {
                    if (epoch == connectionEpoch && currentCoroutineContext().isActive) {
                        mutable.update { it.copy(sessionsLoading = false) }
                    }
                }
            }
    }

    fun createSession(
        title: String? = null,
        onOpened: (String) -> Unit = {},
    ) {
        launchSessionCreation(title = title, onOpened = onOpened)
    }

    private fun launchSessionCreation(
        title: String? = null,
        recoverySession: String? = null,
        promptId: String? = null,
        onOpened: (String) -> Unit,
    ) {
        if (creationJob?.isActive == true) return
        val runtime = sessionRuntime
        val saved = profile
        if (runtime == null || saved == null) {
            mutable.update { it.copy(operationError = "Not connected to the dashboard — session was not created") }
            return
        }
        val epoch = connectionEpoch
        creationJob =
            scope.launch {
                val creation =
                    SessionCreation(
                        state = { mutable.value },
                        update = { transform -> mutable.update(transform) },
                        runtime = runtime,
                        create = {
                            check(epoch == connectionEpoch && foreground) { "Connection changed — session was not created" }
                            val id = gateway.createSession(title, selectedProfile(saved))
                            check(epoch == connectionEpoch && foreground) { "Connection changed — session creation outcome unknown" }
                            id
                        },
                        opened = { id ->
                            cancelVoice()
                            threadJob?.cancel()
                            persist { it.copy(selectedSessionId = id) }
                            onOpened(id)
                        },
                        submit = ::submit,
                    )
                if (recoverySession != null && promptId != null) {
                    creation.recover(recoverySession, promptId)
                } else {
                    creation.start()
                }
            }
    }

    fun openSession(
        id: String,
        remembered: Boolean = false,
    ) {
        cancelVoice()
        val saved = profile ?: return
        val runtime = sessionRuntime
        val epoch = connectionEpoch
        threadJob?.cancel()
        val row = saved.readPositions[id]
        mutable.update {
            it.copy(
                selectedSession = id,
                messages = if (it.selectedSession == id) it.messages else emptyList(),
                positionRow = row,
                sessionsRequired = false,
                positionOffset = saved.readOffsets[id] ?: 0,
                threadLoading = true,
                operationError = null,
            )
        }
        persist { it.copy(selectedSessionId = id) }
        threadJob =
            scope.launch {
                try {
                    val endpoint = Endpoint.parse(saved.endpoint)
                    if (row != null) {
                        val window = api.messagesAround(endpoint, id, row, profile = selectedProfile(saved))
                        mutable.update {
                            it.copy(
                                messages = window.messages,
                                threadHasOlder = window.hasOlder,
                                threadHasNewer = window.hasNewer,
                            )
                        }
                    } else {
                        val page = api.messages(endpoint, id, profile = selectedProfile(saved))
                        mutable.update { it.copy(messages = page.messages, threadHasOlder = page.hasOlder, threadHasNewer = false) }
                    }
                    checkNotNull(runtime) { "Not connected to the dashboard — session was not resumed" }.resume(id)
                    reconcileRunning(epoch)
                    if (remembered && mutable.value.conversations[id]?.stream != null) refreshLatest(id)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (remembered && error is ApiException && error.httpCode == 404) {
                        mutable.update { it.clearMissingSession(id) }
                        persist {
                            it.copy(
                                selectedSessionId = null,
                                readPositions = it.readPositions - id,
                                readOffsets = it.readOffsets - id,
                            )
                        }
                    } else {
                        operationFailure(error)
                    }
                } finally {
                    if (currentCoroutineContext().isActive) mutable.update { it.copy(threadLoading = false) }
                }
            }
    }

    fun loadOlder() {
        val current = mutable.value
        val id = current.selectedSession ?: return
        val saved = profile ?: return
        if (current.threadLoading || !current.threadHasOlder) return
        val anchor = current.messages.firstOrNull()?.rowId ?: return
        mutable.update { it.copy(threadLoading = true, operationError = null) }
        threadJob =
            scope.launch {
                try {
                    val window = api.messagesAround(Endpoint.parse(saved.endpoint), id, anchor, profile = selectedProfile(saved))
                    mutable.update {
                        it.copy(
                            messages = (window.messages + it.messages).distinctBy { row -> row.rowId }.sortedBy { row -> row.rowId },
                            threadHasOlder = window.hasOlder && window.messages.any { row -> row.rowId < anchor },
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    operationFailure(error)
                } finally {
                    if (currentCoroutineContext().isActive) mutable.update { it.copy(threadLoading = false) }
                }
            }
    }

    fun loadNewer() {
        val current = mutable.value
        val id = current.selectedSession ?: return
        val saved = profile ?: return
        if (current.threadLoading || !current.threadHasNewer) return
        val anchor = current.messages.lastOrNull()?.rowId ?: return
        mutable.update { it.copy(threadLoading = true, operationError = null) }
        threadJob =
            scope.launch {
                try {
                    val window = api.messagesAround(Endpoint.parse(saved.endpoint), id, anchor, profile = selectedProfile(saved))
                    mutable.update {
                        it.copy(
                            messages = (it.messages + window.messages).distinctBy { row -> row.rowId }.sortedBy { row -> row.rowId },
                            threadHasNewer = window.hasNewer && window.messages.any { row -> row.rowId > anchor },
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    operationFailure(error)
                } finally {
                    if (currentCoroutineContext().isActive) mutable.update { it.copy(threadLoading = false) }
                }
            }
    }

    fun loadBots() {
        val saved = profile ?: return
        if (mutable.value.botsLoading) return
        botsJob =
            scope.launch {
                mutable.update { it.copy(botsLoading = true, botsLoaded = false, operationError = null) }
                val counts = linkedMapOf<String, BotSource>()
                val seen = mutableSetOf<String>()
                var offset = 0
                try {
                    do {
                        val page = api.sessions(Endpoint.parse(saved.endpoint), offset = offset)
                        page.sessions.forEach { row ->
                            if (!seen.add(row.id)) return@forEach
                            val id = row.profile?.let { "profile:$it" } ?: row.source?.let { "source:$it" }
                            if (id != null) {
                                counts[id] =
                                    BotSource(
                                        id,
                                        row.profile ?: row.source.orEmpty(),
                                        row.source,
                                        row.profile,
                                        (counts[id]?.count ?: 0) + 1,
                                    )
                            }
                        }
                        mutable.update { it.copy(bots = counts.values.toList()) }
                        offset += page.sessions.size
                    } while (page.hasMore && page.sessions.isNotEmpty())
                    mutable.update { it.copy(botsLoaded = true) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    operationFailure(error)
                } finally {
                    if (currentCoroutineContext().isActive) mutable.update { it.copy(botsLoading = false) }
                }
            }
    }

    fun selectBot(source: String?) {
        cancelVoice()
        threadJob?.cancel()
        historyJob?.cancel()
        sessionsSearchJob?.cancel()
        history.invalidate()
        persist { it.copy(botProfile = source, selectedSessionId = null) }
        mutable.update {
            it.copy(
                selectedBot = source,
                selectedSession = null,
                sessions = emptyList(),
                messages = emptyList(),
                positionRow = null,
                sessionsQuery = "",
                sessionsSearch = null,
                sessionsSearchLoading = false,
                historyAction = null,
                historyBusy = false,
                historyError = null,
            )
        }
        loadSessions(reset = true)
    }

    fun refreshReadPosition() {
        val saved = profile ?: return
        val session = mutable.value.selectedSession ?: return
        mutable.update {
            it.copy(positionRow = saved.readPositions[session], positionOffset = saved.readOffsets[session] ?: 0)
        }
    }

    fun rememberPosition(
        sessionId: String,
        rowId: Int,
        offset: Int,
    ) {
        if (rowId <= 0) return
        val saved = profile ?: return
        profile =
            saved.copy(
                readPositions = saved.readPositions + (sessionId to rowId),
                readOffsets = saved.readOffsets + (sessionId to offset),
            )
        positionSaveJob?.cancel()
        positionSaveJob =
            scope.launch {
                delay(250)
                saveProfile()
            }
    }

    private fun persist(transform: (Profile) -> Profile) {
        val saved = profile ?: return
        profile = transform(saved)
        scope.launch { saveProfile() }
    }

    private suspend fun saveProfile() {
        withContext(Dispatchers.IO) {
            try {
                saveMutex.withLock { profile?.let(store::save) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(operationError = "Unable to save encrypted profile") }
            }
        }
    }

    private fun operationFailure(error: Exception) {
        mutable.update { it.copy(operationError = error.message ?: "Dashboard request failed") }
    }

    fun sessionsShown() {
        mutable.update { it.copy(sessionsRequired = false) }
    }

    fun searchSessions(query: String) {
        sessionsSearchJob?.cancel()
        history.search(query)
        if (query.trim().length < 2) return
        sessionsSearchJob =
            scope.launch {
                delay(250)
                history.loadSearch(query)
            }
    }

    fun showSessionActions(id: String) {
        history.showActions(id)
    }

    fun beginSessionRename() {
        history.beginRename()
    }

    fun beginSessionDelete() {
        history.beginDelete()
    }

    fun dismissSessionActions() {
        history.dismiss()
    }

    fun renameSession(title: String) {
        launchHistory { history.renameSession(title) }
    }

    fun confirmSessionDelete() {
        launchHistory { history.confirmSessionDelete() }
    }

    private fun launchHistory(block: suspend () -> Unit) {
        if (historyJob?.isActive == true) return
        historyEpoch = connectionEpoch
        historyJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                block()
            }
    }

    private fun sessionDeleted(
        id: String,
        selected: Boolean,
        loaded: Boolean,
    ) {
        if (loaded && sessionsOffset > 0) sessionsOffset -= 1
        refreshJobs.remove(id)?.cancel()
        queuedChecks.remove(id)?.cancel()
        eventRevisions.remove(id)
        activityRevisions.remove(id)
        if (selected) {
            cancelVoice()
            threadJob?.cancel()
        }
        persist { saved ->
            saved.copy(
                selectedSessionId = saved.selectedSessionId?.takeUnless { it == id },
                readPositions = saved.readPositions - id,
                readOffsets = saved.readOffsets - id,
            )
        }
    }

    fun voicePermissionDenied() {
        mutable.update { it.copy(voiceFeedback = "Microphone permission not granted — enable in LightOS Settings") }
    }

    fun startVoice(onTranscript: ((String) -> Unit)? = null) {
        cancelVoice()
        val saved = profile
        val session = mutable.value.selectedSession
        if (saved == null || (session == null && onTranscript == null)) {
            mutable.update { it.copy(voiceFeedback = "transcription needs the dashboard") }
            return
        }
        val endpoint = Endpoint.parse(saved.endpoint)
        voice =
            Listener(
                scope = scope,
                transcribe = { pcm -> api.transcribe(endpoint, WavAudio.dataUrl(pcm)) },
                onState = { state ->
                    val line =
                        when (state) {
                            Listener.State.Idle -> null
                            Listener.State.Listening, Listener.State.Transcribing -> null
                            Listener.State.NoSpeech -> "no speech"
                            Listener.State.Error -> "transcription needs the dashboard"
                        }
                    mutable.update { it.copy(voiceState = state, voiceFeedback = line) }
                },
                onResult = { transcript ->
                    val callback = onTranscript
                    if (callback != null) {
                        callback(transcript)
                    } else {
                        session?.let { id ->
                            val previous =
                                mutable.value.conversations[id]
                                    ?.composer
                                    .orEmpty()
                            val text = if (previous.isBlank()) transcript else "${previous.trimEnd()} $transcript"
                            editDraft(id, text)
                        }
                    }
                },
            ).also { listener ->
                voiceTelemetry =
                    scope.launch {
                        launch { listener.level.collect { mutableVoiceLevel.value = it } }
                        launch { listener.partial.collect { mutableVoicePartial.value = it } }
                    }
                listener.start()
            }
    }

    /** Release finishes dictation into the draft; sending always requires a separate action. */
    fun releaseVoice() {
        voice?.release()
    }

    fun cancelVoice() {
        voice?.cancel()
        voice = null
        voiceTelemetry?.cancel()
        voiceTelemetry = null
        mutableVoiceLevel.value = 0f
        mutableVoicePartial.value = ""
        mutable.update { it.copy(voiceState = Listener.State.Idle, voiceFeedback = null) }
    }

    fun editDraft(
        session: String,
        text: String,
    ) {
        updateConversation(session) { it.copy(composer = text) }
    }

    private fun updateConversation(
        session: String,
        transform: (Conversation) -> Conversation,
    ) {
        mutable.update {
            it.copy(conversations = it.conversations + (session to transform(it.conversations[session] ?: Conversation())))
        }
    }

    fun submit(
        session: String,
        text: String,
    ) {
        if (text.isBlank()) return
        val saved = profile ?: return
        val runtime = sessionRuntime ?: return
        val epoch = connectionEpoch
        val needsBaseline = mutable.value.threadHasNewer
        val id = "phone-${++nextPromptId}"
        val baseline =
            if (needsBaseline) {
                Int.MAX_VALUE
            } else {
                mutable.value.messages
                    .lastOrNull()
                    ?.rowId ?: 0
            }
        updateConversation(session) { current ->
            val submitted = current.submit(id, text, baseline)
            val next =
                submitted.copy(
                    prompts = submitted.prompts.map { if (it.id == id) it.copy(status = PromptStatus.PREPARING) else it },
                )
            if (current.composer.isNotBlank() && current.composer.trim() != text.trim()) {
                next.copy(composer = current.composer)
            } else {
                next
            }
        }
        scope.launch {
            try {
                val latestBaseline =
                    if (needsBaseline) {
                        api
                            .messages(Endpoint.parse(saved.endpoint), session, profile = selectedProfile(saved))
                            .messages
                            .lastOrNull()
                            ?.rowId ?: 0
                    } else {
                        baseline
                    }
                runtime.resume(session)
                val active = reconcileRunning(epoch)
                val queued = session in active
                updateConversation(session) { current ->
                    current.copy(
                        prompts =
                            current.prompts.map {
                                if (it.id == id) {
                                    it.copy(
                                        baselineRowId = latestBaseline,
                                        queued = queued,
                                        status = if (queued) PromptStatus.QUEUED else PromptStatus.PENDING,
                                    )
                                } else {
                                    it
                                }
                            },
                    )
                }
                val result = runtime.submit(session, text, queued)
                if (epoch != connectionEpoch) {
                    throw GatewayRpcException("Dashboard connection changed — reconcile before resending", RpcFailureKind.CONNECTION_LOST)
                }
                updateConversation(session) { it.submitResult(id, result) }
                startQueuedCheck(session, id, runtime, saved, epoch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayRpcException) {
                updateConversation(session) {
                    if (error.kind == RpcFailureKind.TIMEOUT || error.kind == RpcFailureKind.CONNECTION_LOST) {
                        it.submitUnknown(id, error.message)
                    } else {
                        it.submitResult(id, null, error.message ?: "Prompt rejected", rejectionCode = error.code)
                    }
                }
            } catch (error: Exception) {
                updateConversation(session) { it.submitResult(id, null, error.message ?: "Prompt was not sent") }
            }
        }
    }

    private fun resumeQueuedChecks() {
        val runtime = sessionRuntime ?: return
        val saved = profile ?: return
        for ((session, conversation) in mutable.value.conversations) {
            for (prompt in conversation.prompts) {
                startQueuedCheck(session, prompt.id, runtime, saved, connectionEpoch)
            }
        }
    }

    private fun startQueuedCheck(
        session: String,
        id: String,
        runtime: SessionRuntime,
        saved: Profile,
        epoch: Int,
    ) {
        if (!foreground || queuedChecks[id]?.isActive == true) return
        val prompt =
            mutable.value.conversations[session]
                ?.prompts
                ?.firstOrNull { it.id == id } ?: return
        if (prompt.status != PromptStatus.QUEUED &&
            !(prompt.promoted && (prompt.status == PromptStatus.PENDING || prompt.status == PromptStatus.ACCEPTED))
        ) {
            return
        }
        queuedChecks[id] =
            scope.launch {
                try {
                    QueuedPromptDelivery(
                        conversation = { mutable.value.conversations[session] ?: Conversation() },
                        update = { transform -> updateConversation(session, transform) },
                        poll = {
                            check(epoch == connectionEpoch && foreground) { "Dashboard connection changed — reconcile before resending" }
                            val revision = eventRevisions[session] ?: 0L
                            val active = reconcileRunning(epoch)
                            val page = api.messages(Endpoint.parse(saved.endpoint), session, profile = selectedProfile(saved))
                            check(epoch == connectionEpoch) { "Dashboard connection changed — reconcile before resending" }
                            if (revision != (eventRevisions[session] ?: 0L)) {
                                null
                            } else {
                                updateConversation(session) { it.reconcile(page.messages) }
                                session in active
                            }
                        },
                        cancel = { prompt ->
                            check(epoch == connectionEpoch && foreground) { "Dashboard connection changed — reconcile before resending" }
                            val params = prompt.cancelParams(runtime.runtimeId(session))
                            if (params == null) {
                                false
                            } else {
                                val result = gateway.request("prompt.cancel", params) as? JsonObject
                                val status = (result?.get("status") as? JsonPrimitive)?.content?.lowercase()
                                val outcome = (result?.get("outcome") as? JsonPrimitive)?.content?.lowercase()
                                check(epoch == connectionEpoch) { "Dashboard connection changed — reconcile before resending" }
                                status in CANCELLED_STATUSES || outcome in CANCELLED_STATUSES
                            }
                        },
                        submit = { text ->
                            check(epoch == connectionEpoch && foreground) { "Dashboard connection changed — prompt was not sent" }
                            val result = runtime.submit(session, text, queued = false)
                            if (epoch != connectionEpoch) {
                                throw GatewayRpcException(
                                    "Dashboard connection changed — reconcile before resending",
                                    RpcFailureKind.CONNECTION_LOST,
                                )
                            }
                            result
                        },
                    ).confirm(id)
                } finally {
                    if (queuedChecks[id] === currentCoroutineContext()[Job]) queuedChecks.remove(id)
                }
            }
    }

    /** Resolve stable request ids against current hoisted state, never a stale list index. */
    fun activate(
        session: String,
        action: String,
        onSessionCreated: (String) -> Unit = {},
    ) {
        val conversation = mutable.value.conversations[session] ?: Conversation(running = session in mutable.value.liveSessions)
        when {
            action == "stop" -> stop(session)
            action.startsWith("new-chat:") ->
                launchSessionCreation(
                    recoverySession = session,
                    promptId = action.removePrefix("new-chat:"),
                    onOpened = onSessionCreated,
                )
            action.startsWith("retry:") -> {
                val id = action.removePrefix("retry:")
                val prompt =
                    conversation.prompts.firstOrNull {
                        it.id == id && it.status == PromptStatus.FAILED && it.rejectionCode != SESSION_CONFLICT_CODE
                    } ?: return
                updateConversation(session) { it.copy(prompts = it.prompts.filterNot { row -> row.id == id }) }
                submit(session, prompt.text)
            }
            action.startsWith("cancel:") -> cancelQueued(session, action.removePrefix("cancel:"))
            else -> {
                for (approval in conversation.approvals) {
                    for (choice in approval.allowedChoices()) {
                        if (action == "approval:${approval.rowKey()}:$choice") {
                            respondApproval(session, approval, choice)
                            return
                        }
                    }
                }
                for (clarify in conversation.clarifications) {
                    if (clarify.status != DecisionStatus.PENDING) continue
                    val key = clarify.rowKey()
                    if (action == "clarify:$key:send") {
                        respondClarify(session, clarify)
                        return
                    }
                    for (question in clarify.questions()) {
                        for ((index, choice) in question.choices.withIndex()) {
                            if (action != "clarify:$key:${question.id}:$index") continue
                            val selected = clarify.toggleChoice(question.id, choice)
                            updateConversation(session) {
                                it.copy(clarifications = it.clarifications.map { row -> if (row.rowKey() == key) selected else row })
                            }
                            if (clarify.questions().size == 1 && !question.multiSelect) respondClarify(session, selected)
                            return
                        }
                    }
                }
            }
        }
    }

    private fun stop(session: String) {
        val runtime = sessionRuntime ?: return
        val current = mutable.value.conversations[session] ?: Conversation(running = session in mutable.value.liveSessions)
        if (!current.running || current.stopping) return
        updateConversation(session) { it.copy(running = true).beginStop() }
        scope.launch {
            try {
                val id = runtime.resume(session)
                gateway.request("session.interrupt", buildJsonObject { put("session_id", id) })
                updateConversation(session) { it.stopResult() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateConversation(session) { it.stopResult(error.message ?: "Stop failed") }
            }
        }
    }

    private fun cancelQueued(
        session: String,
        id: String,
    ) {
        val prompt =
            mutable.value.conversations[session]
                ?.prompts
                ?.firstOrNull { it.id == id } ?: return
        if (prompt.status != PromptStatus.QUEUED) return
        val params = prompt.cancelParams(runtimeId(session)) ?: return
        updateConversation(session) { it.cancelQueued(id) }
        scope.launch {
            try {
                val result = gateway.request("prompt.cancel", params) as? JsonObject
                val status = (result?.get("status") as? JsonPrimitive)?.content?.lowercase()
                val outcome = (result?.get("outcome") as? JsonPrimitive)?.content?.lowercase()
                val cancelled = status in CANCELLED_STATUSES || outcome in CANCELLED_STATUSES
                updateConversation(session) {
                    it.cancelQueuedResult(id, if (cancelled) null else "Cancellation not confirmed — reconcile with gateway")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateConversation(session) { it.cancelQueuedResult(id, error.message ?: "Cancellation failed") }
            } finally {
                if (currentCoroutineContext().isActive) resumeQueuedChecks()
            }
        }
    }

    private fun respondApproval(
        session: String,
        approval: ApprovalDecision,
        choice: String,
    ) {
        val params = approval.respondParams(choice, runtimeId(approval.request.sessionId ?: session)) ?: return
        val id = approval.request.requestId
        updateConversation(session) { it.markApprovalSending(id, choice) }
        scope.launch {
            try {
                val result = gateway.request("approval.respond", params) as? JsonObject
                val resolved = (result?.get("resolved") as? JsonPrimitive)?.content?.toIntOrNull()
                val status = (result?.get("status") as? JsonPrimitive)?.content
                when {
                    resolved != null && resolved > 0 || status == "resolved" || status == "already_resolved" ->
                        mutable.update { it.settleApproval(session, id) }
                    resolved == 0 -> Unit // Confirm absence with the authoritative pending query.
                    else -> error("Approval response was not accepted")
                }
                refreshAttention()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateConversation(session) { it.markApprovalError(id, error.message ?: "Approval response failed") }
            }
        }
    }

    private fun respondClarify(
        session: String,
        clarify: ClarifyDecision,
    ) {
        if (clarify.questions().any { it.id !in clarify.lockedAnswers && clarify.answerFor(it.id).isNullOrBlank() }) return
        val calls = clarify.responseCalls(runtimeId(clarify.request.sessionId ?: session))
        if (calls.isEmpty()) return
        updateConversation(session) { it.markClarifySending(clarify.request.clarifyId) }
        scope.launch {
            try {
                for (call in calls) gateway.request(call.method, call.params)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateConversation(session) { it.markClarifyError(clarify.request.clarifyId, error.message ?: "Answer failed") }
            }
        }
    }

    /** A valid snapshot may clear stale flags; newer gateway activity always wins the race. */
    private suspend fun reconcileRunning(epoch: Int): Set<String> {
        val revisions = activityRevisions.toMap()
        val active = activeSessionIds(gateway.request("session.active_list", buildJsonObject {}), ::storedId)
        check(epoch == connectionEpoch) { "Dashboard connection changed before live state was checked" }
        val changed = activityRevisions.keys.filter { activityRevisions[it] != revisions[it] }.toSet()
        mutable.update { it.reconcileActivity(active, changed) }
        return mutable.value.liveSessions
    }

    private fun refreshAttention() {
        attentionJob?.cancel()
        val epoch = connectionEpoch
        attentionJob =
            scope.launch {
                try {
                    val active = reconcileRunning(epoch)
                    val sessions =
                        (
                            mutable.value.desktopNotices.keys + active +
                                listOfNotNull(
                                    mutable.value.selectedSession?.takeIf { id -> mutable.value.sessions.any { it.id == id } },
                                )
                        ) - GLOBAL_NOTICE
                    for (session in sessions) {
                        val revision = eventRevisions[session] ?: 0L
                        val result =
                            gateway.request(
                                "approval.pending",
                                buildJsonObject { put("session_id", runtimeId(session)) },
                            )
                        val approvals =
                            (result as? JsonObject)?.get("approvals") as? JsonArray
                                ?: continue
                        if (epoch != connectionEpoch || revision != (eventRevisions[session] ?: 0L)) continue
                        val events =
                            approvals.mapNotNull { approval ->
                                val payload = approval as? JsonObject ?: return@mapNotNull null
                                val frame =
                                    buildJsonObject {
                                        put("method", "event")
                                        put(
                                            "params",
                                            buildJsonObject {
                                                put("type", "approval.request")
                                                put("session_id", session)
                                                put("payload", payload)
                                            },
                                        )
                                    }
                                val parsed =
                                    dev.loosewire.hermeslight.net.GatewayEventParser
                                        .parse(frame.toString())
                                (parsed as? dev.loosewire.hermeslight.net.ParsedFrame.Event)?.event as? GatewayEvent.ApprovalRequested
                            }
                        mutable.update { current ->
                            var next = current
                            val present = events.map { it.requestId ?: "approval:$session" }.toSet()
                            for (notice in current.desktopNotices[session].orEmpty()) {
                                if (!notice.clarification && notice.id !in present) {
                                    next = next.settleApproval(session, notice.id)
                                }
                            }
                            for (event in events) next = reduceGatewayEvent(next, event)
                            next
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (epoch == connectionEpoch) operationFailure(error)
                }
            }
    }

    /** Replace transient rows only with an authoritative snapshot that no newer event outran. */
    private fun refreshLatest(session: String) {
        val saved = profile ?: return
        refreshJobs[session]?.cancel()
        val revision = eventRevisions[session] ?: 0L
        val epoch = connectionEpoch
        refreshJobs[session] =
            scope.launch {
                try {
                    val endpoint = Endpoint.parse(saved.endpoint)
                    val active = reconcileRunning(epoch)
                    val page = api.messages(endpoint, session, profile = selectedProfile(saved))
                    if (epoch != connectionEpoch || revision != (eventRevisions[session] ?: 0L)) return@launch
                    mutable.update { current ->
                        val conversation = current.conversations[session] ?: Conversation()
                        val reconciled = conversation.copy(running = session in active).reconcile(page.messages)
                        current.copy(
                            messages = if (current.selectedSession == session) page.messages else current.messages,
                            threadHasOlder = if (current.selectedSession == session) page.hasOlder else current.threadHasOlder,
                            threadHasNewer = if (current.selectedSession == session) false else current.threadHasNewer,
                            conversations = current.conversations + (session to reconciled),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (epoch == connectionEpoch && mutable.value.selectedSession == session) operationFailure(error)
                }
            }
    }

    private fun runtimeId(session: String): String = sessionRuntime?.runtimeId(session) ?: session

    private fun storedId(session: String): String = sessionRuntime?.storedId(session) ?: session

    private fun receive(event: GatewayEvent) {
        val session = event.sessionId?.let(::storedId)
        if (menuVisible && event is GatewayEvent.Change) {
            if (event.type == "cron.changed" || event.type == "sessions.changed") loadMenu()
            if (event.type == "sessions.changed") loadBots()
        }
        session?.let { eventRevisions[it] = (eventRevisions[it] ?: 0L) + 1 }
        if (event is GatewayEvent.MessageStart || event is GatewayEvent.MessageDelta ||
            event is GatewayEvent.MessageInterim || event is GatewayEvent.MessageDone ||
            event is GatewayEvent.Done || event is GatewayEvent.Failure || event is GatewayEvent.SessionStatus
        ) {
            session?.let { activityRevisions[it] = (activityRevisions[it] ?: 0L) + 1 }
        }
        mutable.update { reduceGatewayEvent(it, event, session) }
        if ((event is GatewayEvent.Done || event is GatewayEvent.MessageDone || event is GatewayEvent.Failure) &&
            session == mutable.value.selectedSession
        ) {
            session?.let(::refreshLatest)
        }
        val waiting =
            mutable.value.desktopNotices[session]
                .orEmpty()
                .any { !it.clarification }
        if (event is GatewayEvent.ApprovalPending || event is GatewayEvent.ApprovalReceived ||
            waiting && (event is GatewayEvent.Done || event is GatewayEvent.Failure)
        ) {
            refreshAttention()
        }
    }
}

private val CANCELLED_STATUSES = setOf("cancelled", "canceled")

private fun selectedProfile(profile: Profile): String? = profile.botProfile?.takeIf { it.startsWith("profile:") }?.removePrefix("profile:")
