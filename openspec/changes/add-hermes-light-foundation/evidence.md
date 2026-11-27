## 2026-11-26 — M0 implementation and static verification

Built the `:tool` SDK composite scaffold, endpoint/auth/REST/JSON-RPC data plane, Keystore-backed
AES-GCM profile storage, and Home/Sessions/Thread/Bots/Setup read-only screens. Wheel focus/paging,
per-session read anchors, reconnect status, and desktop-only approval/clarify notices are wired.
No sending, streaming rendering, voice, approvals interaction or live deck data was added.

Verification after the last code edit:

- `./gradlew -Porg.gradle.java.installations.paths="$(mise where java@temurin-17)" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain` — **BUILD SUCCESSFUL**; 52 tests, zero failures/errors/skips.
- `grep -r -n androidx.compose.material3 tool/src/main` — no matches. Annotated transcript spans
  use foundation `BasicText` with SDK tokens because this SDK's `LightText` accepts only strings.
- `apksigner verify --verbose --print-certs tool/build/outputs/apk/debug/tool-debug.apk` — verifies
  with v2 signing, one RSA-3072 signer, `CN=Hermes Light Development`. Private dev key remains in
  `~/keys/hermes-light/`, outside this repository. Tool Manager upload was not executed.

Local lane: owner's SDK `main`, revision `251237ef3778c907c5dbd89e84685532c9d99a28`, with existing
uncommitted SDK changes left untouched. JDK 21 builds the tool; JDK 17 compiles the included SDK
plugin. AGP 8.12.3 warns that SDK 37 exceeds its tested SDK 36; the build passes without suppressing
that warning. The SDK also emits an existing publication-variant warning.

No adb, emulator driving, screenshots, real-phone install or live-dashboard verification was
performed. Parent must verify LP3 layout/wheel/back, password and loopback first-connect, session
paging/position restoration, dashboard-loss recovery and actual Android Keystore persistence.
Visual/install acceptance boxes remain unchecked; no specs have been synced as accepted behavior.
