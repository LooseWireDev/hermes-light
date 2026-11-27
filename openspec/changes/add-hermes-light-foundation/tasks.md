# Tasks — add-hermes-light-foundation (M0)

One commit per task group. ktlint + color guard green at every commit; verify on the LP3 AVD before
ticking anything visual. Do not start M1 features early (no sending, no voice, no deck data).

## 1. Scaffold

- [ ] 1.1 New Gradle project: `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper; Kotlin + Compose + `com.thelightphone.sdk` dependency per light-sdk docs (`docs/building`).
- [ ] 1.2 `tool/build.gradle.kts`: package `dev.loosewire.hermeslight`, module `:tool` per the SDK layout, SDK via includeBuild(../light-sdk/plugin), compileSdk 37, minSdk 34, ktlint + checkColorLiterals tasks mirroring light-tern; `NOTICE` with the Apache-2.0 attribution for hermes-mobile-derived protocol handling.
- [ ] 1.3 Manifest + `MainActivity : SealedLightActivity`, AMOLED black theme via `LightThemeTokens`, empty Home screen that renders "Hermes Light" and the wheel contract stub.
- [ ] 1.4 `./gradlew assembleDebug` + install on the LP3 AVD; screenshot of the booting shell.

## 2. Data plane

- [ ] 2.1 `net/Endpoint.kt`: parse/derive base + WS URLs (http/https → ws/wss, `?token=`/`?ticket=`), unit-tested.
- [ ] 2.2 `net/Auth.kt`: `GET api/status` probe (25s watchdog, honest error), provider discovery, loopback token extraction from SPA, `POST /auth/password-login`, `POST api/auth/ws-ticket`; unit tests for probe states (ok / gated / unreachable / timeout).
- [ ] 2.3 `net/Api.kt`: sessions family GETs (`api/sessions`, `api/sessions/{id}`, `.../messages`, `.../messages/around`) with kotlinx.serialization models, error mapping.
- [ ] 2.4 `data/Store.kt`: encrypted-at-rest profile (URL, credential, selected ids, per-session read position); unit test round-trip.
- [ ] 2.5 `net/Gateway.kt`: JSON-RPC WS connect (`hello`, `client.capabilities`), backoff reconnect, event parse for `message.*`, `reasoning.*`, `approval.*`, `clarify.*`, `process.*`, `done`, `error`; M0 may ignore frames it does not render, but must not crash on unknown types.

## 3. Screens (read-only)

- [ ] 3.1 `SetupScreen`: URL field + sign-in/token paths, connect test row, status line; persisted profile; acceptance: loopback token connect works on the AVD, wrong URL shows an honest error within 25s.
- [ ] 3.2 `SessionsScreen`: Light row list (title / source / age / live dot), wheel scroll, click opens thread; footer "Bots" row; acceptance: ≥50 sessions listed, wheel reaches the last row, fetch paged on wheel-end.
- [ ] 3.3 `ThreadScreen`: bottom-anchored transcript with minimal markdown (bold, inline code, fenced code, underlined links), tool/reasoning one-liners, wheel-up paging via `messages/around`, position restore per session.
- [ ] 3.4 `BotsScreen`: rows derived from session sources/profiles with counts; click switches thread filter.
- [ ] 3.5 `HomeScreen`: deck placeholder zone (empty state text only) + thread below + status line; wheel-up from thread reaches deck; wheel-up from deck opens Sessions.
- [ ] 3.6 Navigation + back semantics per design.md; no Material3 chrome anywhere (`grep -r androidx.compose.material3 app/src/main` must only match justified exceptions, listed in the commit).

## 4. Honesty and failure states

- [ ] 4.1 Status line everywhere: connecting / connected / reconnecting (n) / error text; verified by killing the dashboard mid-run.
- [ ] 4.2 Read-only socket: if approval/clarify frames arrive, render a non-interactive "approval pending — respond on desktop" row (interactive approvals are M1).
- [ ] 4.3 Cold start with no profile lands on Setup, never on an empty thread.

## 5. Install on the phone

- [ ] 5.1 Tool metadata complete (`lighttool.toml`, uploadTool task per light-tern), dev-key signing (new keystore at `~/keys/hermes-light/`, documented in AGENTS.md).
- [ ] 5.2 Debug APK sideloaded to the real LP3 via Tool Manager or Borealis; owner confirms boot + first connect.
- [ ] 5.3 Evidence: screenshot set in `~/Projects/light/hermes-light-audit/` and a short `evidence.md` appended to this change.

## Out of scope for M0 (do not implement)

Sending, streaming render, approvals interaction, voice/`WheelTalk`, deck data tiles, notifications,
search, long-press menus, kanban/files/cron admin.
