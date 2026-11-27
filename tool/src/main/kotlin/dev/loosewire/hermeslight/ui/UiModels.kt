package dev.loosewire.hermeslight.ui

/*
 * The complete, immutable surface the screens need from the application layer.
 *
 * Screens never reach into networking, storage or the SDK. The parent screen/wrapper owns all
 * state and supplies one of these models plus the matching actions. All positions are in the
 * units named by the property: list positions are indices into the supplied row list, thread
 * positions are a message id plus a pixel offset.
 */

/** Tone of the one connection line shown on every screen. */
enum class StatusTone {
    Idle,
    Busy,
    Ok,
    Warn,
    Error,
}

/** Honest link state: connecting, connected, reconnecting (n), or the error text. */
data class StatusLineModel(
    val text: String,
    val tone: StatusTone = StatusTone.Idle,
)

/** A position in a wheel-driven list. [index] is an index into the screen's row list. */
data class ListPosition(
    val index: Int,
    val offsetPx: Int = 0,
)

// -- Home -----------------------------------------------------------------------------------------

data class StatusStripUi(
    val summary: String,
    /** Null opens the current thread, which also displays unscoped dashboard notices. */
    val sessionId: String?,
)

data class HomeUi(
    val statusStrip: StatusStripUi? = null,
    val thread: ThreadUi,
)

data class HomeActions(
    val onOpenStatus: (String?) -> Unit = {},
    /** Opens the full Thread at a message id, or at the newest message when null. */
    val onOpenThread: (String?) -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onOpenBots: () -> Unit = {},
    /** Home and the full Thread share the same read position and older paging. */
    val threadActions: ThreadActions = ThreadActions(),
    val onBack: () -> Unit = {},
    val onLongBack: () -> Unit = {},
)

// -- Sessions -------------------------------------------------------------------------------------

data class SessionRowUi(
    val id: String,
    val title: String,
    val source: String,
    /** Already formatted by the application: "now", "4m", "2h", "Tue". */
    val age: String,
    val live: Boolean = false,
    val profileId: String? = null,
    val detail: String? = null,
)

data class SessionsUi(
    val sessions: List<SessionRowUi> = emptyList(),
    val profileId: String? = null,
    val selectedSessionId: String? = null,
    /** Restore point. [ListPosition.index] indexes [sessions]. */
    val position: ListPosition? = null,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val loadError: String? = null,
    val status: StatusLineModel = StatusLineModel(""),
)

data class SessionsActions(
    val onOpenSession: (String) -> Unit = {},
    val onOpenBots: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onPositionChanged: (ListPosition) -> Unit = {},
    val onBack: () -> Unit = {},
    val onLongBack: () -> Unit = {},
)

// -- Thread ---------------------------------------------------------------------------------------

enum class ThreadMessageKind {
    User,
    Assistant,
    Tool,
    Reasoning,
    Approval,
    Clarify,
    Error,
    System,
}

data class ThreadMessageUi(
    val id: String,
    val kind: ThreadMessageKind,
    /** Markdown-ish body. Ignored for collapsed Tool/Reasoning rows until expanded. */
    val body: String = "",
    /** Already formatted local time. Revealed on demand by the thread. */
    val timestamp: String? = null,
    val toolName: String? = null,
    /** "ok", "fail", or "unknown"; a tool result never implies the entire turn is done. */
    val toolOutcome: String? = null,
    val title: String? = null,
    val origin: String? = null,
    /** Full raw text shown when a Tool/Reasoning row is expanded. Defaults to [body]. */
    val expandableText: String? = null,
    /** Label for an unsupported attachment/media block, e.g. "image: diagram.png". */
    val mediaLabel: String? = null,
    /** Explicit row activation; null retains transcript expansion/timestamp behavior. */
    val action: String? = null,
)

enum class ThreadNoticeKind {
    Approval,
    Clarify,
}

/** Read-only M0 notice. The phone must not offer a way to answer it. */
data class ThreadNoticeUi(
    val id: String,
    val kind: ThreadNoticeKind,
    val title: String,
    val origin: String? = null,
    val detail: String? = null,
)

/** Restore point for a read-only transcript. [messageId] is the first visible row. */
data class ThreadPosition(
    val messageId: String,
    val offsetPx: Int = 0,
)

data class ThreadUi(
    val sessionId: String? = null,
    val title: String = "",
    val botName: String = "June",
    /** Oldest first, newest last. The reader bottom-anchors the last row. */
    val messages: List<ThreadMessageUi> = emptyList(),
    /** Awaiting-desktop approval/clarify notices, newest last. Never actionable. */
    val notices: List<ThreadNoticeUi> = emptyList(),
    /** Restore point supplied by the application; null starts at the bottom. */
    val position: ThreadPosition? = null,
    val hasOlder: Boolean = true,
    /** A restored window can sit in the middle of history; newer rows load on wheel-down. */
    val hasNewer: Boolean = false,
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val error: String? = null,
    val emptyText: String = "No messages yet.",
    val status: StatusLineModel = StatusLineModel(""),
    val composer: String = "",
    val running: Boolean = false,
)

data class ThreadActions(
    val onLoadOlder: () -> Unit = {},
    val onLoadNewer: () -> Unit = {},
    val onPositionChanged: (ThreadPosition) -> Unit = {},
    val onBack: () -> Unit = {},
    val onLongBack: () -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onSubmit: (String) -> Unit = {},
    val onAction: (String) -> Unit = {},
)

// -- Bots -----------------------------------------------------------------------------------------

data class BotRowUi(
    val id: String,
    val name: String,
    val source: String,
    val sessionCount: Int = 0,
    val profileId: String? = null,
    val detail: String? = null,
)

data class BotsUi(
    val bots: List<BotRowUi> = emptyList(),
    val profileId: String? = null,
    val selectedBotId: String? = null,
    /** Restore point. [ListPosition.index] indexes [bots]. */
    val position: ListPosition? = null,
    val loading: Boolean = false,
    val loadError: String? = null,
    val emptyText: String = "No bots yet.",
    val status: StatusLineModel = StatusLineModel(""),
)

data class BotsActions(
    val onOpenBot: (String) -> Unit = {},
    val onLoadBots: () -> Unit = {},
    val onPositionChanged: (ListPosition) -> Unit = {},
    val onBack: () -> Unit = {},
    val onLongBack: () -> Unit = {},
)

// -- Setup ----------------------------------------------------------------------------------------

enum class AuthModeUi {
    Token,
    Password,
}

data class ProviderUi(
    val id: String,
    val label: String,
    val available: Boolean = true,
)

data class SetupUi(
    val endpoint: String = DEFAULT_ENDPOINT,
    val mode: AuthModeUi = AuthModeUi.Token,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val providers: List<ProviderUi> = emptyList(),
    /** e.g. "Providers: password" or "Providers unavailable". */
    val providerStatus: String = "",
    val connection: StatusLineModel = StatusLineModel("not tested"),
    val testing: Boolean = false,
    val error: String? = null,
)

data class SetupActions(
    val onEndpointChange: (String) -> Unit = {},
    val onModeChange: (AuthModeUi) -> Unit = {},
    val onTokenChange: (String) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onTestConnection: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onLongBack: () -> Unit = {},
)
