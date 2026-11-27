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

## 2026-11-26 — Parent device acceptance (LP3 AVD, 1080x1240 @ 420dpi)

Debug APK installed on `emulator-5584` and driven against a loopback dashboard (`127.0.0.1:9121`
via `adb reverse`, token mode). Screenshots in `~/Projects/light/hermes-light-audit/` (private).

Verified:
- Boots as an SDK tool (`com.thelightphone.sdk.LightActivity`), black background, Light typography.
- Setup: server row, token/password modes, honest error (`Dashboard address is required`) before input.
- Token connect against the real dashboard succeeds; status line reads `connected`.
- Home: DECK placeholder + June thread with real messages, tool and reasoning one-liners; status line present.
- Wheel: DPAD up/down scrolls the thread and pages back through history; continuing past the top moves
  focus to the deck and then opens Sessions. 300 wheel-ups from a 600-message thread reached the top
  and handed off correctly.
- Sessions: real session rows with source/age/snippet; "New session — sending arrives in M1" row.
- Open session → Thread renders long real messages; Back returns to Sessions.

Defects found (fix before M1 completes):
1. Setup shows the endpoint as prefilled text while the underlying state is empty — indistinguishable
   from a saved value, and the first `Test connection` fails confusingly. Show explicit hint styling or
   seed the state with the default value.
2. Tool rows render raw ids (`· tool: call_6f46f80da33b4227b0ae66f9`) instead of the tool name and
   outcome promised by the spec's one-liner rule.
3. Sessions is only reachable by wheeling the entire thread to its top; with 600-message sessions that
   is hundreds of turns. Needs a direct affordance (candidate: hold back on Home, or make the deck
   header open Sessions).
4. Secondary gray (timestamps, DECK label, status line) is thin on black at 420dpi — review contrast
   against LightThemeTokens before M1.

Untested here: Bots screen, dashboard-loss recovery line, encrypted-store cold restart with a stored
profile, real-phone install (M0 task 5.2).

## 2026-11-26 — M0.1 menu and acceptance fixes

Implemented 6.1–6.4: full SDK Menu page (Chat / History / Bots / disabled Cron / Settings), Home
Menu action and deck-top wheel entry, SDK back chain Thread → Sessions → Menu → Home, and Chat
returning to the existing Home/session/read anchor. Setup now holds an editable default endpoint
without saving a profile. Tool result rows resolve names from row/envelope metadata or assistant
call ids, show `ok` / `fail` (`unknown` for absent results), and only fall back to call ids when
names are unavailable. Secondary text moves from `contentSecondary` to the SDK's next `content`
tone; disabled titles and dividers retain the secondary tone. No new color literals or M1 features.

Verification after the final code edit:
- With `ANDROID_HOME=$HOME/.local/share/light-tern-toolchain/android-sdk`, `JAVA_HOME=$HOME/.local/opt/temurin21`, and Java 21 on `PATH`:
  `./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain` — **BUILD SUCCESSFUL**; **61 tests, zero failures/errors/skips**.
- Nine new tests cover Menu/back routing, direct Home-to-Thread parents, Chat without a duplicate
  Home, bot/Settings navigation, cold-start endpoint seeding without persistence, explicit endpoint
  deletion, tool outcomes/names/fallbacks, and tool-name resolution after older-page loading.
- Initial gate run found only ktlint filename/signature/chain formatting issues; corrected before
  the successful run. Existing SDK publication and compile-SDK-37 warnings remain unchanged.
- Debug artifact: `tool/build/outputs/apk/debug/tool-debug.apk`; local/development composite SDK
  `main` at `251237ef3778c907c5dbd89e84685532c9d99a28`, pre-existing SDK changes untouched.

No adb, emulator driving, screenshots, device install or live-dashboard check performed, as requested.
Parent still owns LP3-shaped Menu/back/scroll and contrast acceptance; visual boxes remain unchecked.

## 2026-11-26 — M0.1 device acceptance (menu page + fixes)

Screenshots in `~/Projects/light/hermes-light-audit/` (private).

- Menu action present on Home; opens the full-page Menu (Chat / History / Bots / Cron / Settings),
  Cron disabled with "arrives with live tiles", status line persists.
- Back chain exercised end to end: Thread → Sessions → Menu → Home.
- Tool one-liners now render names and outcomes (`· web_search — ok`) instead of `call_*` ids.
- Gates green (ktlint, color guard, unit tests incl. new menu-routing tests, assembleDebug).

New defect found: with a **stale remembered session id** at cold start, the app surfaces a raw
"load failed … Not found on the dashboard (HTTP 404)" hint at the top of Sessions (the dashboard
answers 200 for every live endpoint; the 404 is the remembered session itself). Fix: on 404 for a
remembered session, clear the selection and land on Sessions silently. Fold into M1.

## 2026-11-26 — M0.2 credentials paste

Setup now has wheel/touch Paste rows for Server, Token, Username and Password, direct form-state
updates without the SDK editor, trimmed input, masked secrets, three-second length-only/empty
feedback, and the Bitwarden hint. Four Android-free handler tests cover trimming, blank/no-op,
secret-safe feedback and long-token lengths beyond the 48-bullet display cap. Metadata: 101 / 0.1.1.

Implementation API deviation: the active SDK plugin rejects `LocalContext` imports/usages, so the
system clipboard is read through Compose `LocalClipboard.getClipEntry()` instead. No SDK policy
changes, autofill implementation or new dependencies. Local/development composite SDK `main` at
`251237ef3778c907c5dbd89e84685532c9d99a28`; pre-existing SDK changes left untouched.

No adb/emulator driving, screenshots, phone install, release build/signing, upload or push.
Parent owns LP3 layout/wheel/clipboard-to-connect acceptance and the release artifact.

Verification after the final code edit, with the requested Android/JDK environment:
`./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain`
— **BUILD SUCCESSFUL in 3s**. All four gates passed, including the four new paste-handler tests.
The initial run found only ktlint filename/chain formatting issues; fixed before the successful
run. Existing SDK publication and AGP compile-SDK-37 warnings remain. Debug artifact:
`tool/build/outputs/apk/debug/tool-debug.apk`.

## 2026-11-26 — M0.2 device acceptance (paste)

Verified on the LP3 AVD with the signed 0.1.1 (code 101) release build. The emulator has no
`cmd clipboard`, so the clipboard was seeded from a real EditText (WebView address bar, select-all +
copy) — the same path Bitwarden's Copy takes.

- `Paste Server` inserted the clipboard text verbatim into the Server field.
- `Paste Token` masked the value (32 dots for a 32-character clipboard string) and kept the value
  usable for Test/Sign in.
- Hint line "Copied a password from Bitwarden? Use Paste." renders under the credential rows.

## 2026-11-26 — M0.3 Home actions and conditional status strip

Removed Menu and the deck models/placeholder. Home now has SDK History/Bots bar actions, long-back
Setup, and Thread → Sessions → Home back routing. Wheel-up stays in the transcript (older paging
remains). One attention line prioritizes approvals, then clarifications, then running sessions;
activation opens the relevant thread, never answers a notice. No cron/unread state exists yet.
Idle emits no layout nodes or spacer. Metadata: **102 / 0.1.2**.

Final verification with the requested Android/JDK environment:
`./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain`
— **BUILD SUCCESSFUL in 3s; 77 tests, zero failures/errors/skips**. Coverage includes the new back
chain, attention counts/priority/settlement, idle composition emitting no layout nodes (therefore
zero reserved height, even with stale focus), and wheel-only action traversal with an inert oldest
thread boundary. Idle-after-`done` and desktop approval settlement are also covered.

The SDK rejects Robolectric even as a test dependency; no SDK policy was changed. Composition-only
tests use the official AndroidX desktop runtime on the unit-test runtime classpath, with the BOM's
version; Android compile/APK dependencies are unchanged. Initial gate failures were that dependency
restriction, Android Trace stubs in the unit-test runtime, and one ktlint chain; all resolved.
Local/development SDK `main` at `251237ef3778c907c5dbd89e84685532c9d99a28`, existing SDK edits untouched.
Artifact: `tool/build/outputs/apk/debug/tool-debug.apk`. No adb/emulator, screenshots, phone install,
release signing, upload or push. LP3 visual/wheel acceptance remains unperformed.

Wheel-only reachability: down past the newest message focuses History → Bots → active strip →
thread; empty Home starts at History and skips absent content. The SDK bar/strip text marks focus
with `›`; no extra chrome, page or hold gesture. Reverse traversal returns to the newest thread,
but up at its oldest boundary never crosses focus. A final ktlint nested-conditional issue was
fixed before the successful four-gate run above. LP3 visual acceptance is still unperformed.

## 2026-11-27 — M0.3 device acceptance (menu removed, conditional strip)

Signed 0.1.2 (code 102) release build on the LP3 AVD.

- Home top bar: `› History` and `Bots` actions; no MENU action, no menu page in the build.
- DECK header and placeholder removed — with nothing pending the thread starts at the top of the
  content area (strip renders zero height).
- Thread renders real messages with `· terminal — ok` tool one-liners.
- Status-strip appearance with live work (running turn / pending approval) remains unverified on
  device: the read-only socket does not surface those events in M0 — M1 wires the strip to live state.
