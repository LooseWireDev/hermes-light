package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.AuthMode
import dev.loosewire.hermeslight.data.Profile
import dev.loosewire.hermeslight.data.ProfileStore
import dev.loosewire.hermeslight.data.SessionSummary
import dev.loosewire.hermeslight.data.TranscriptMessage
import dev.loosewire.hermeslight.net.Api
import dev.loosewire.hermeslight.net.Auth
import dev.loosewire.hermeslight.net.AuthProvider
import dev.loosewire.hermeslight.net.Endpoint
import dev.loosewire.hermeslight.net.Gateway
import dev.loosewire.hermeslight.net.GatewayConnection
import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.net.GatewayState
import dev.loosewire.hermeslight.net.ProbeResult
import dev.loosewire.hermeslight.net.SignInResult
import dev.loosewire.hermeslight.net.TicketResult
import dev.loosewire.hermeslight.net.WsAuthParam
import dev.loosewire.hermeslight.net.defaultHttpClient
import dev.loosewire.hermeslight.ui.DEFAULT_ENDPOINT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
    val bots: List<BotSource> = emptyList(),
    val botsLoading: Boolean = false,
    val selectedBot: String? = null,
    val selectedSession: String? = null,
    val messages: List<TranscriptMessage> = emptyList(),
    val threadLoading: Boolean = false,
    val threadHasOlder: Boolean = false,
    val threadHasNewer: Boolean = false,
    val positionRow: Int? = null,
    val positionOffset: Int = 0,
    val liveSessions: Set<String> = emptySet(),
    val desktopNotices: Map<String, List<DesktopNotice>> = emptyMap(),
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
    private var botsJob: Job? = null
    private var positionSaveJob: Job? = null
    private var sessionsOffset = 0
    private var connectionEpoch = 0
    private val client = defaultHttpClient()
    private val api = Api(client, tokenProvider = { profile?.takeIf { it.authMode == AuthMode.TOKEN }?.token })
    private val auth = Auth(client)
    private val saveMutex = Mutex()
    private var handshake: CompletableDeferred<Unit>? = null
    private val gateway =
        Gateway(
            scope,
            object : Gateway.Listener {
                override fun onState(state: GatewayState) {
                    mutable.update { it.copy(status = connectionLabel(state)) }
                    when (state) {
                        GatewayState.Connected -> handshake?.complete(Unit)
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
        foreground = false
        connectionJob?.cancel()
        sessionsJob?.cancel()
        threadJob?.cancel()
        botsJob?.cancel()
        gateway.onForeground(false)
        mutable.update { it.copy(connecting = false, sessionsLoading = false, threadLoading = false, botsLoading = false) }
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
        connectionJob?.cancel()
        sessionsJob?.cancel()
        threadJob?.cancel()
        botsJob?.cancel()
        mutable.update { it.copy(sessionsLoading = false, threadLoading = false, botsLoading = false) }
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
        gateway.disconnect()
        mutable.update { it.copy(connecting = true, status = "connecting…", operationError = null) }
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
                mutable.update {
                    it.copy(
                        configured = true,
                        profileVersion = it.profileVersion + if (persist) 1 else 0,
                        endpoint = authenticated.endpoint,
                        selectedBot = authenticated.botProfile,
                        selectedSession = authenticated.selectedSessionId,
                        sessions = if (persist) emptyList() else it.sessions,
                        messages = if (persist) emptyList() else it.messages,
                        desktopNotices = if (persist) emptyMap() else it.desktopNotices,
                        liveSessions = if (persist) emptySet() else it.liveSessions,
                    )
                }
                loadSessions(reset = true)
                authenticated.selectedSessionId?.let { openSession(it) }
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

    fun loadSessions(reset: Boolean = false) {
        val saved = profile ?: return
        if (mutable.value.sessionsLoading && !reset) return
        sessionsJob?.cancel()
        val bot = mutable.value.selectedBot
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
                    sessionsOffset = offset + page.sessions.size
                    mutable.update {
                        it.copy(
                            sessions = ((if (reset) emptyList() else it.sessions) + page.sessions).distinctBy { row -> row.id },
                            sessionsHaveMore = page.hasMore,
                        )
                    }
                    if (mutable.value.selectedSession == null) {
                        page.sessions.firstOrNull()?.let { openSession(it.id) }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    operationFailure(error)
                } finally {
                    if (currentCoroutineContext().isActive) mutable.update { it.copy(sessionsLoading = false) }
                }
            }
    }

    fun openSession(id: String) {
        val saved = profile ?: return
        threadJob?.cancel()
        val row = saved.readPositions[id]
        mutable.update {
            it.copy(
                selectedSession = id,
                messages = if (it.selectedSession == id) it.messages else emptyList(),
                positionRow = row,
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
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    operationFailure(error)
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
                mutable.update { it.copy(botsLoading = true, operationError = null) }
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
        threadJob?.cancel()
        persist { it.copy(botProfile = source, selectedSessionId = null) }
        mutable.update {
            it.copy(selectedBot = source, selectedSession = null, sessions = emptyList(), messages = emptyList(), positionRow = null)
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

    private fun receive(event: GatewayEvent) {
        mutable.update { reduceGatewayEvent(it, event) }
        if ((event is GatewayEvent.Done || event is GatewayEvent.MessageDone) &&
            event.sessionId == mutable.value.selectedSession
        ) {
            // M0 never renders deltas: refresh authoritative REST only on completion.
            mutable.value.selectedSession?.let(::openSession)
        }
    }
}

private fun selectedProfile(profile: Profile): String? = profile.botProfile?.takeIf { it.startsWith("profile:") }?.removePrefix("profile:")
