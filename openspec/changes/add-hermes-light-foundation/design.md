# Design — Hermes Light foundation (M0)

## Shape of the app

One activity (`SealedLightActivity`), six destinations, no drawer, no tab bar, no sheets:

```
Home ── deck tiles (M2) ──┐
   └── thread (June)      │ wheel up
Sessions ◀── wheel back ──┘
Bots ◀── from Sessions row footer
Setup ◀── long-press back / first run
Approvals ── rendered inline in thread (M1)
```

Wheel contract (identical everywhere):
- **Turn** moves a focus list one row (`WheelScroll`); at the thread's end, turning further reaches
  the deck; at the deck's top, turning further opens the Sessions overlay.
- **Click** opens the focused row (session → thread; bot → thread of that bot; deck tile → target).
- **Hold** = push-to-talk (`WheelTalk`); release sends, turn-to-cancel.
- **Back** = one level up; back at Home = nothing (stay, per LightOS convention).

## Screen inventory (M0)

| # | Screen | Content | Data |
| --- | --- | --- | --- |
| 1 | Setup | Server URL field, "Sign in" (password form) or "Use token", connection test row, honest error line | `GET api/status`, `api/auth/providers`, `POST /auth/password-login`, loopback SPA token |
| 2 | Home | Deck placeholder (M0: empty state "Deck arrives in M2"), June thread below, status line | same as Thread |
| 3 | Sessions | List of sessions: title / source / age / live dot; footer row "Bots"; top row "New session" (M1 sends) | `GET api/sessions?limit=50&order=recent&exclude_sources=cron` |
| 4 | Thread | Transcript, wheel-scrolled, bottom-anchored; reasoning/tool one-liners; streaming caret (M1) | `GET api/sessions/{id}/messages?order=latest&limit=50&include_compacted=true`, `.../messages/around` |
| 5 | Bots | Rows: name + source tag; maps to session selection | `GET api/sessions?source=` + bot ids from config (M0: derived from sessions) |
| 6 | Approvals | inline rows in Thread (M1); M0 shows a read-only "approval pending" line if the event arrives | WS `approval.request` |

## Data layer

- `net/Endpoint.kt` — base URL parsing (http/https), `resolve()`, WS URL derivation (`wss://`,
  `?token=` or `?ticket=`).
- `net/Auth.kt` — probe `api/status` (`auth_required`), provider discovery, password login
  (`POST /auth/password-login` → session cookie), or loopback token mode (SPA `__HERMES_SESSION_TOKEN__`),
  `POST api/auth/ws-ticket` for the gated WS credential. Bounded by a watchdog (25s) with an honest
  error — copy the lesson, not the code, from the fork.
- `net/Api.kt` — sessions family only in M0: `sessions`, `sessions/{id}`, `sessions/{id}/messages`,
  `messages/around`; OkHttp + kotlinx.serialization (strict, unknown keys ignored).
- `net/Gateway.kt` — JSON-RPC WS: `hello`/`client.capabilities`, reconnect with backoff, event
  reducer for `message.*`, `reasoning.*`, `approval.*`, `clarify.*`, `process.*`, `done`, `error`.
  In M0 the socket may be read-only (no turn submission).
- `data/Store.kt` — persisted profile: base URL, credential (encrypted at rest via Android Keystore),
  selected session/bot, last thread positions.

## Light SDK usage

- Widgets: `LightText` variants for all type, `LightTopBar` only where a title is needed (Home has
  none — the deck is the top), `LightBarButton` for row actions, `SimpleLightScreen` for full screens.
- Tokens: `LightThemeTokens` spacing/colors; AMOLED black background, white text, gray secondary.
- Input: `com.gios.light.common.hw.WheelScroll` (from BrightHermes, owner's code) wrapped for our
  lists; `WheelTalk` verbatim for M1.
- No Material3 components for chrome. If a component is missing (e.g. a progress line), draw it with
  `Canvas`/`Box` per tokens.

## Thread rendering (M0 read-only)

- Message rows: role glyph (you / June), body text with minimal markdown (bold, inline code, code
  blocks, links as underlined text), timestamp on demand.
- Tool calls and reasoning collapse to a single `· tool: name — ok` row (tap expands to raw text).
- Streaming: M0 renders what the REST transcript holds; M1 adds `message.delta` frames with a caret.
- Long transcripts: bottom-anchored list; wheel-up loads `messages/around` in 50-message windows;
  position preserved across session switches (stored per session id).

## Voice input (M1, ported from BrightHermes — owner requirement)

BrightHermes already solves the hard part: **unbounded-length hold-to-talk**. Port, do not reinvent:

- `voice/DictationTake.kt` — the take is a sequence of audio chunks; decoding happens in overlapping
  windows (`next()`), and `complete()` stitches each window's tokens onto the previous text using a
  word-anchor search (`findAnchor(words, previous)`), so a ten-minute hold produces one coherent
  transcript with bounded memory and no duplication at window seams.
- `voice/Listener.kt` — SpeechRecognizer loop with bounded live previews, a single commit point on
  release, cancel path, and permission/warmup handling.
- `hw/WheelTalk.kt` — the physical contract: hold = talk, release = send, turn = cancel; whole panel
  inverts while listening so it reads in bright sunlight; the hint line doubles as on-screen PTT.

Hermes Light keeps all three behaviours verbatim; the only change is where the finished text goes —
through the dashboard gateway (`command.dispatch`/turn submission) instead of the bridge's `user`
frame. Voice MUST remain available on every screen that accepts text (Home/thread), including while
streaming a reply.

## Failure and honesty rules

- Every network state has a visible line: `connecting…`, `connected`, `reconnecting (n)`, `error: …`.
- No optimistic send in M0; nothing is shown as sent until the gateway acknowledges (M1 rule).
- Approval prompts never auto-accept; absence of the socket is shown, never hidden behind "done".

## Repository setup

```
hermes-light/
├── AGENTS.md  README.md  NOTICE
├── docs/research/00-two-apps-and-apis.md
├── openspec/…  (this change)
├── settings.gradle.kts (includeBuild ../light-sdk/plugin)  build.gradle.kts  gradle/libs.versions.toml  gradlew…
└── app/
    ├── build.gradle.kts        # :tool, package dev.loosewire.hermeslight, minSdk per LP3 (34), target 37
    └── src/main/
        ├── AndroidManifest.xml # single activity, tool metadata for LightOS install
        └── kotlin/dev/loosewire/hermeslight/
            ├── MainActivity.kt ui/{Home,Sessions,Thread,Bots,Setup}Screen.kt
            ├── ui/common/{LightRow,Wheel*,StatusLine}.kt
            ├── net/{Endpoint,Auth,Api,Gateway}.kt
            ├── data/{Store,Models}.kt
            └── json/  (kotlinx models for REST + WS)
```

Tool packaging (`lighttool.toml`, manifest metadata, dev-key signing) is part of M0's last task so the
app can be installed on the phone via Tool Manager/Borealis from day one.

## Verification protocol

Build with the local toolchain (JDK 21 for lint/release), install on `emulator-5584`
(`mealforge_lpi3_ui_test_v1`, 1080×1240 @ 420dpi), drive with `adb` and screenshot every screen into
`~/Projects/light/hermes-light-audit/`. Point it at a loopback dashboard through
`adb reverse tcp:9121 tcp:9121` for deterministic data, then once at the owner's real dashboard.
