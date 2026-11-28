## 2026-11-27 — M1 implementation scope and protocol evidence

Extending the M0.3 socket/UI, not replacing the data plane. Local/development lane: the owner's
composite SDK `main` at `251237ef3778c907c5dbd89e84685532c9d99a28`; existing SDK edits left untouched.
No adb/emulator driving, screenshots, install, upload or push. Parent owns LP3 visual/wheel acceptance.
No specs have been synced as accepted device behavior.

Contract details that affect safety:

- `prompt.cancel` requires both `session_id` and the submit receipt's `admission_id`. A JSON-RPC
  success alone does not prove cancellation; inspect admission status/outcome.
- `approval.respond` uses choices `once`/`deny`/`always`; `Always` is not blanket `all:true`.
  It must be explicitly permitted by the request. RPC acknowledgement does not fabricate settlement.
- Canonical clarify replies echo `prompt_id` and `execution_generation` with `answer`; legacy
  replies use `response`/`answer` and the request/question identifiers.
- `approval.pending` queries pending approval details. Clarify has no pending-query RPC; the gateway
  replays `clarify.request` on reconnect.

Voice source mismatch discovered before porting: the current owner sources use sherpa-onnx's
`OfflineRecognizer` with Parakeet assets and `AudioRecord`, not Android `SpeechRecognizer`.
`DictationTake` explicitly limits capture to 300 seconds. The requested verbatim port cannot
honestly be described as unbounded or as a SpeechRecognizer loop. No alternate dictation pipeline
will be substituted without an owner decision.

### Safety correction to the proposed approval lifecycle

The reference gateway's `app/src/test/resources/gateway-contract/openrpc.json` defines
`approval.received` as a display acknowledgement, not a decision. It must not hide an unanswered
request. Rows/attention therefore remain pending on that event. Confirmed `approval.respond`
resolution or absence from `approval.pending` settles a request; `approval.cancelled` expires the
named requests. Tests that previously equated display acknowledgement with settlement are updated.
Canonical approval requests carrying `prompt_id` and `execution_generation` echo those fences;
legacy requests retain the supplied `request_id`/`choice`/`all:false` contract.

### Voice owner decisions required

1. Allow a supported sherpa-onnx artifact and model distribution through the SDK, and provide an
   approved capture/assets interface, or explicitly authorize a different recognizer/capture port.
   The active plugin blocks `android.content.Context` (line 140), rejects local file dependencies
   (line 519), and does not allowlist sherpa-onnx. No SDK policy was weakened and no unbuildable
   `Listener` stub was added.
2. Preserve the source's 300-second cap, or authorize changing its behavior to meet the unbounded
   requirement. The port preserves the cap; it does not claim ten-minute capture.
3. The SDK exposes permission check/request but no permission-settings action; raw
   `android.content.Intent` and lifecycle-owner access are blocked. The UI can request microphone
   permission through LightOS and rechecks it when the SDK screen is shown. Missing permission
   names LightOS Settings manually. A real Settings action needs an SDK API/owner-approved route.

`DictationTake` and the SDK-key-adapted `WheelTalk` are owner-attributed ports. `WheelTalk`'s private
BrightHermes wheel-bus interface is replaced by an `onTurn(steps, whileHeld)` callback using SDK
device keys; its hold/release/cancel state machine remains unchanged. It is not connected to a
nonexistent recognizer. No hold, capture, inversion or voice-send success is claimed in this build.

The canonical Obsidian vault was unavailable at `/home/gav/Documents/obsidian-vault`; no vault note
was changed. This evidence file contains the durable state and blockers.

### Implemented surfaces awaiting parent device acceptance

- Shared per-session composer and submission state; typed SEND/QUEUE goes through `prompt.submit`.
  Pending/queued/rejected/unknown outcomes stay distinct. Retry is explicit; an unknown transport
  outcome never triggers a blind resend. Cancellation uses the admission id and requires an
  explicit cancelled receipt. Stop does not fabricate `done`.
- Streaming/reasoning/tool/process reduction, assistant caret, collapsed one-line tool/reasoning
  rows, and REST reconciliation after completion/reconnect with socket-generation protection.
- Explicit blocking approval/clarify rows with hoisted state, first-choice wheel focus, gated
  Always, multi-question/multi-select answers, canonical generation fences and terminal states.
- Active-session/pending-approval snapshots feed the zero-height-idle strip. A remembered-session
  404 clears selection/read anchors silently and requests Sessions.
- Metadata is `103 / 0.2.0`. This is not alpha4 publication or full M1 voice acceptance.

All code is integrated before running gates; no builds/tests/lint run between task-group edits.
The parent retains visual acceptance boxes because this run is explicitly forbidden to drive the
AVD. No release signing or publishing is performed here.

### Final verification after the last code edit

With `ANDROID_HOME=$HOME/.local/share/light-tern-toolchain/android-sdk`,
`JAVA_HOME=$HOME/.local/opt/temurin21`, and Java 21 on PATH:

```sh
./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug :tool:assembleRelease --continue --console=plain
```

**BUILD SUCCESSFUL in 8s**. **140 tests across 19 files; zero failures, errors or skips**.
Coverage includes correlated/out-of-order RPC responses, rejection/disconnect/background/timeout/
replacement generations, submission/queue/stop, REST row baselines and identical repeated sends,
approval acknowledgement versus settlement/expiry, canonical decision fences, clarify locked
answers/generation replay, missing remembered-session state, and seven DictationTake tests including
the 180-second synthetic take (360 words, exactly one final send).

Initial gate runs exposed ktlint layout, internal coroutine continuation APIs, erased generic
helper JVM signatures, invalid test fixtures/expectations, and a real locked-answer default bug.
These were corrected; the final gate run above passed after the last code edit. Continuations now
use stable `resumeWith` with single-completion ownership enforced by the pending-call map.
The SDK's existing publication warning and AGP's compile-SDK-37 warning remain.

Artifacts:
- `tool/build/outputs/apk/debug/tool-debug.apk`
- `tool/build/outputs/apk/release/tool-release-unsigned.apk`

The release is unsigned; the parent signs/publishes only after acceptance. No adb/emulator,
screenshots, live dashboard, phone install, release signing, upload or push was performed.
Visual boxes, full voice tasks and parent publication remain unchecked. This is a verified local
build of the reachable work, not a claim that M1 or alpha4 is fully accepted.

## 2026-11-27 — M1 device acceptance, part 1 (send path)

Signed 0.2.0 (code 103) on the LP3 AVD against the loopback dashboard.

Working: composer opens the SDK editor, SEND submits, and the failure path is honest — the dashboard's
rejection rendered as an ERROR row with `· Retry` and the draft was restored (`· Draft: M1`). Nothing
was shown as delivered. Voice surfaces exist and report `Microphone permission not granted` truthfully.

Defect (blocks send): `prompt.submit` was rejected with `code 4001: session not found` — the app
submits the **stored** session id, but a turn needs the session's current/runtime id. Reference
client resolves it with `GET api/sessions/{id}/latest-descendant` and URL-encodes ids (they may
contain `/`). Fix in M1.1: resolve descendant before submit, cache, re-resolve once on 4001, and
audit all session-id path encoding. Streaming and approvals remain unverified until a submit is
accepted.

## 2026-11-27 — task group 9: M1.1 live-id resolution

- `SessionRuntime` resolves `GET api/sessions/{stored-id}/latest-descendant` before the first
  `prompt.submit`, caches per stored id within the authenticated connection, and refreshes/retries
  once only after an explicit rejected RPC with code 4001 and `session not found`. A second such
  rejection reports “Session not found after refreshing its current id”; the existing failed-row
  and draft-restoration path handles it. Timeout/lost-socket outcomes never trigger a resend.
- REST session detail, messages, messages/around and latest-descendant append ids with OkHttp
  `addPathSegment`, encoding `/` and reserved characters once while retaining proxy prefixes.
  JSON session ids remain raw for submit, interrupt, cancellation and approval/clarify calls.
  Runtime pushes/active snapshots map back to stored conversation keys; reply payloads retain the
  original runtime identity. Old aliases remain until connection replacement for late events.
- The disabled New session row now says “create on desktop for now”; no creation RPC or UI added.
- Regression coverage: descendant submit/cache isolation, a single 4001 retry, bounded repeated
  rejection/draft restoration, no retry for unknown outcomes or unrelated errors, failed resolution
  without submit, slash/reserved-character REST paths, and runtime-to-stored stream routing.
- Reference checkout was read only. `SessionOpsResponses.LatestDescendantResponse` defines the
  `session_id` response, and `ActiveSessionHolder` distinguishes stored/runtime identity.
  Attribution is retained in derived sources and `NOTICE`.
- Local/development lane; unchanged owner SDK `main` at
  `251237ef3778c907c5dbd89e84685532c9d99a28`, including its pre-existing edits. No dependency,
  metadata, version, signing or SDK changes. No adb/emulator, live gateway, install, upload or push.
  Device confirmation of the send blocker and LP3 copy/layout remains outstanding; no visual
  acceptance or accepted-spec sync is claimed.
- Canonical Obsidian vault remains unavailable (`/home/gav/Documents/obsidian-vault/Home.md`
  not found); this note records the intended durable project update instead.
- Verification after the last code edit, using the requested Android/JDK environment and
  `-Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21"`:
  `./gradlew ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain`
  — **BUILD SUCCESSFUL in 4s; 148 tests across 20 files, zero failures/errors/skips**.
  The first run found one ktlint chain-wrapping issue; it was fixed before this final green run.
  Debug artifact: `tool/build/outputs/apk/debug/tool-debug.apk`.

## 2026-11-27 — task group 10: M1.2 runtime resume

- Corrects task group 9's protocol assumption: `latest-descendant` returns the stored transcript
  tip, not a runtime id. Reference `RpcMethods.SESSION_RESUME`, `SessionPrompt` and
  `ChatViewModel.resumeSession` confirm `session.resume` with `source: "desktop"` returns the
  runtime `session_id`. Reference files remain read only; derived-source attribution and `NOTICE`
  retain Apache-2.0 provenance.
- Thread opening and the first submit share a serialized, cached resume per stored session:
  descendant (stored tip) → resume (runtime id) → submit. Resume sends `omit_messages: true`
  because REST already hydrates the transcript, and includes the selected profile when present.
  Submit and interrupt use the resumed runtime id; incoming runtime events map to the original
  stored conversation. Old runtime aliases remain available for late events.
- An explicit `4001 session not found` re-resolves and resumes once before retrying unchanged
  prompt text/queue state. A second refusal is terminal. Submit timeout/socket loss never resends.
  Resume failure has a specific error and restores an unsent draft, including a resume timeout:
  an unknown resume outcome is not an unknown prompt outcome.
- Unit coverage extends the previous identity/retry cases with a distinct stored tip/runtime id,
  open-before-submit cache reuse, event routing, overlapping open/submit, resume rejection/timeout
  draft restoration and resume failure during the single 4001 refresh.
- Every gateway RPC failure path emits `Log.d` with method, code and message. Pending RPCs retain
  their existing params by reference solely to redact raw/JSON-escaped values if a rejection
  message echoes them; request bodies are never appended to logs. The active credential and
  case-insensitive token/ticket/password/secret/API-key assignments and Bearer credentials are
  redacted too. Logging cannot change outcomes when the plain JVM Android log stub throws.
  Transport tests cover desktop-source resume, optional profile, missing/invalid runtime ids,
  rejection propagation, echoed prompt/clarification bodies and diagnostic credential formats.
- Local/development lane; existing composite SDK, dependencies, metadata and UI widgets unchanged.
  No adb/emulator, live gateway, install, upload, push or device acceptance in this run. The user
  owns device confirmation; this change does not claim a successful live submit or sync accepted
  device behavior into `openspec/specs/`.
- Canonical Obsidian vault is still unavailable: `/home/gav/Documents/obsidian-vault/Home.md`
  was not found. This evidence note records the durable protocol correction instead.
- Verification after the last code edit, with the requested `ANDROID_HOME`, `JAVA_HOME`, Java 21
  on PATH and Java-installation paths:
  `./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain`
  — **BUILD SUCCESSFUL in 7s; 156 tests across 20 files, zero failures/errors/skips**.
  The initial gate run found ktlint wrapping and a transport-test helper type mismatch; both were
  corrected before the final green run. Existing SDK publication and compile-SDK warnings remain.
  Debug artifact: `tool/build/outputs/apk/debug/tool-debug.apk`.
- Final privacy-hardening verification supersedes the counts above: the same four-gate command
  passed after redacting echoed raw/JSON-escaped request values, overlapping secrets and mixed-case
  credential formats — **BUILD SUCCESSFUL in 6s; 157 tests, zero failures/errors/skips**.

## 2026-11-27 — M1 device acceptance, part 2 (send accepted, streaming, strip)

M1.2 (resume → runtime id) fixed the blocker. Signed 0.2.0 (code 104) on the LP3 AVD, loopback dashboard.

- Send accepted: composing `M1.2 probe` in a live local session produced a delivered `YOU` row (not
  pending, not duplicated) and a turn started.
- Streaming: `· reasoning` row and `terminal` tool row appeared while the reply streamed, with the
  caret `▏` on the assistant row; the final text replaced the caret.
- Live strip: Home showed exactly one line, `1 running`, during the turn, and the thread still filled
  the screen. After completion the strip disappeared (zero height, no label) — verified on Home.
- Sessions copy fixed: `New session / create on desktop for now`.
- Stale/dead session path (earlier this session): honest ERROR + Retry + draft restored, then a
  specific message after a second attempt. No false success.

Unverified on device: approval and clarify row sets (no deterministic trigger from the emulator without
spending an approval-gated tool call), and dictation (AVD has no speech recognizer — covered by the
DictationTake unit tests, including the 3-minute synthetic take). Both stay open for the physical
phone pass on alpha4.

## 2026-11-27 — device probe of task group 10 (M1.2), emulator `mealforge_lpi3_ui_test_v1`

Environment: emulator-5584 (1080×1240 @ 420dpi = 411×472dp), `com.mealforge.lightphone` disabled,
`adb reverse tcp:9121 tcp:9121`, dashboard `--host 127.0.0.1 --port 9121` (token mode), app
`dev.loosewire.hermeslight` 0.2.0 (versionCode 104) built from `2adfcc9`.

Setup: no live session was probed by proxy — the app's current row is the operator's own conversation,
so an isolated session (`m12-probe-*`) was seeded through the gateway first and the phone drove that.

PASS — the M1.2 chain works on device:
- Thread open resolved descendant → resume, and `prompt.submit` was accepted; the phone's text landed in
  the intended stored session (server `messages`: user `probe m12 reply with exactly m12-ok`,
  assistant `m12-ok`).
- Streaming rendered live: `· reasoning` row, then the reply delta with a caret.
- Correct runtime on the server: `model=deepseek-v4.1-flash provider=opencode-go`,
  `tui turn finished: status=complete`, 6.0 s.
- Cold start rehydrated the transcript correctly (`m12-ok`, no caret, no Stop).

FAIL — task group 11 (new): the terminal event never cleared live state. Server logged
`tui turn finished: status=complete` at 06:54:25; at 06:56:17 the same thread still showed
`m12-ok ▏` plus `· Stop`, and the Sessions row still read `● live`. Leaving the thread and reopening it
did not clear it; only `am force-stop` did. Because 10.3 logs RPC *failures* only, the app produced no
logcat trace of the missed event.

Also observed (not defects): the seeded "Test session" row 404s honestly (`Not found on the dashboard
(HTTP 404)`) because that transcript no longer exists; the microphone-permission row is the expected
AVD state with no dictation available.

## 2026-11-27 — wheel task group 11 and Menu v2 task group 12

- Scope is the **Wheel + voice wiring** group 11, not the separate, duplicate-numbered terminal
  event group 11 above. Native SDK key ownership now lives in a `LightScreen` view model with one
  `WheelTalk` instance per screen. `WheelScroll` binds current callbacks without recreating the
  state machine. SDK labels are preferred; raw 19/20/66/80/27 fallback is documented. Both key
  halves are consumed; unrelated keys, Back and Home pass through the wheel handler.
- Thread click sends the existing draft or requests Stop; focused approval/clarify rows retain
  explicit activation. Bare turns drive the thread reader; a pressed turn spends/cancels the
  gesture. List screens use the same native handler, with holds disabled. Composer wheel actions
  remain bound while the SDK keyboard is open.
- First voice use checks RECORD_AUDIO and asks through the SDK once per app lifetime. Missing
  permission names **LightOS Settings**. This is **not a completed voice implementation**:
  `tool/.../voice/Listener.kt` does not exist, the only ported voice implementation is
  `DictationTake`, and the owner Listener requires unallowlisted sherpa-onnx plus absent Parakeet
  assets. A granted hold reports the missing recognizer rather than claiming capture/send.
  The SDK exposes PCM capture, not recognition. The dashboard transcription engine is now tracked
  separately by the owner's newly-added task group 13; it is outside this run's groups 11–12.
  Task 11.2 remains open until that engine is integrated.
- Long-press Thread's status line reads process-wide wheel click/turn counts, current microphone
  grant and SDK launchable-app detection for BrightControl (`com.gios.lightcontrol`); detection
  errors are “unavailable”, not “not installed”. The SDK `app-access` capability is added solely
  for this diagnostic (launcher-scoped package visibility; no QUERY_ALL_PACKAGES).
- **Release/setup note:** BrightControl's per-app rule for `dev.loosewire.hermeslight` must be
  **Off / hands-off** so wheel clicks and camera presses reach Hermes Light.
  `com.thelightphone.*` and `com.gios.*` are hands-off by default; our package is not. When click
  count is zero, diagnostics names this exact rule as the suggested fix.
- Home now exposes a single Menu action. Menu shows connection/model/session state, live History/
  Bots/Cron counts, Settings, About and three app-wide recent sessions. Counts are fetched rather
  than invented; failed Cron fetches are unavailable, not “0 jobs”. Cron and About expand inline,
  keeping six screens. Recent-thread Back follows **Thread → Sessions → Menu → Home**, resolving
  the older contradictory recent-row scenario in favor of the user's explicit chain.
- Metadata: **105 / 0.2.1**. Local/development lane; SDK `main`
  `251237ef3778c907c5dbd89e84685532c9d99a28`, owner fork `gavdevs/light-sdk`, existing SDK edits
  untouched. No SDK policy changes, new dependencies, adb/emulator, install, signing, upload or
  push. LP3 visual/hardware acceptance is unverified and its boxes remain open.
- Obsidian canonical vault remains unavailable: `Home.md` and `Projects/Projects.md` under
  `/home/gav/Documents/obsidian-vault` were not found. This evidence note retains the intended
  durable project update; no vault note was changed.

### Verification after the last code edit

Requested environment (`ANDROID_HOME`, JDK 21 and both Java installation paths), command:

```sh
./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain
```

**BUILD SUCCESSFUL in 5s.** The preceding successful run's XML reports **179 tests in 21 files,
zero failures/errors/skips**; the final run passed after removing an unnecessary AppState copy
from the Menu row projection. The first gate run exposed ktlint wrapping and two new Cron tests
using the shared HTTP client's already-closed executor; tests now use the existing isolated-client
fixture pattern. No production client lifecycle changes were made.

Coverage adds deterministic hold threshold (including delayed main-loop callback), pre-/post-hold
turn cancel, missing/orphan UP, lifecycle reset, shutter cancellation, list mode, SDK/raw mappings,
both key halves and Back/Home fallthrough; Menu back-chain/focus and authenticated Cron count/
unavailable responses. Debug APK: `tool/build/outputs/apk/debug/tool-debug.apk`.
Existing SDK publication and compile-SDK warnings remain. No UI/device acceptance is claimed.

## 2026-11-27 — M1.3 device acceptance (wheel claim + Menu v2)

Signed 0.2.1 (code 105) on the LP3 AVD, loopback dashboard, BrightControl not installed on the AVD.

- Menu v2: Home top bar shows a single `Menu`; the page opens with a status block (`connected`,
  `801 sessions · 1 bot`) and rows `History / Bots / Cron / Settings / About` with live counts
  (`Cron · 0 jobs`, `About · v0.2.1`), then `RECENT SESSIONS` with three rows that open threads.
- Wheel claim: `adb shell input keyevent 19` ×10 on a thread scrolled it to earlier rows
  (`· terminal — ok`, `· reasoning`, `· skill_manage — ok` appeared). The notches are claimed by the
  screen, not forwarded — this is the same key path the phone uses, so the only remaining variable on
  device is BrightControl's per-app rule (owner confirmed: setting the app to Off in Controls makes
  the wheel press work).
- Owner report after alpha4 (before this build): with the app set to Off in Controls, the wheel works;
  voice produced no engine — see task group 13.
- Defect (cosmetic): the status block reads `Model unavailable` when no session is open; it should
  name the model when known and stay quiet otherwise.

## 2026-11-27 — task group 13: M1.4 dashboard voice engine

- The owner selected server-backed recognition. AudioRecord captures VOICE_RECOGNITION,
  16 kHz mono PCM16; required DictationTake windows become 44-byte-header WAV data URLs and
  POST to `api/audio/transcribe`. The existing bearer-token provider/shared password-session
  cookie jar authenticates the requests. No native model, dependency or ABI restriction added.
- The dashboard returns plain text, not word timestamps. The original timestamped anchor path
  remains; text-only completion uses conservative overlap anchors. Missing/ambiguous seams fail
  the whole take rather than fabricate timestamps or silently duplicate/delete words.
  The existing 28-second windows, 24-second stride, 56-second backlog and 300-second cap remain.
- Release commits once. UI state is `listening` while held, `transcribing…` after release,
  `no speech` for empty results, and exactly `transcription needs the dashboard` for failed
  takes. No partial transcript is committed. Home/Thread release appends to the composer;
  editor release explicitly sends-and-closes through the existing submit/queue path.
  Cancel, screen hide/background, editor close and session/profile replacement discard work;
  late permission checks cannot start capture after the gesture ends.
- Metadata: **106 / 0.2.2**. The active change's `design.md` records the future sherpa-onnx /
  Parakeet option: arm64-only AAR, approximately 120 MB model, unavailable on x86_64 emulators.
  Historical SpeechRecognizer/unbounded assumptions are superseded, not advertised as shipped.
- Local/development lane, unchanged owner SDK `main` at
  `251237ef3778c907c5dbd89e84685532c9d99a28`; existing SDK edits left untouched.
  No adb/emulator, live audio/dashboard, install, signing, upload or push. Actual microphone,
  live transcription and LP3 visual/hardware acceptance remain unverified by explicit request.
  Accepted specs are not synced. Canonical Obsidian vault is still unavailable: Home.md and
  Projects/Projects.md were not found under `/home/gav/Documents/obsidian-vault`; this evidence
  retains the durable project update instead.

### Verification after the last code edit

With the requested Android SDK, JDK 21 on PATH and both Java installation paths:

```sh
./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain
```

**BUILD SUCCESSFUL in 10s; 196 tests across 23 files, zero failures/errors/skips.**
The initial gate run passed unit tests and assembly but found ktlint wrapping; the final
four-gate run passed after those formatting-only corrections. Added coverage includes WAV
header/PCM lengths and byte order, data URL round-trip, proxy-prefixed bearer and cookie auth,
empty/malformed/failed transcription responses, exactly three overlapping text windows,
ambiguous/missing anchors, release-once, cancellation/restart, empty audio and empty transcript,
failed later windows without partial commits, and bounded-backlog overflow.

Artifact: `tool/build/outputs/apk/debug/tool-debug.apk`. No new runtime dependency/model.
Existing SDK publication/compile-SDK warnings remain; the JVM fake microphone's monitor also
produces Kotlin's Java-Object style warning. No runtime/visual acceptance or vault update claimed.

## 2026-11-27 — M1.4 voice engine (server path proven, hold path not device-testable)

Signed 0.2.2 (code 106) on the LP3 AVD; dashboard loopback.

- Engine lands: `voice/Listener.kt` (AudioRecord, 16 kHz mono PCM16, DictationTake windows),
  `voice/WavAudio.kt` (WAV writer), transcription via `POST api/audio/transcribe`.
- Dashboard STT proven independently of the app: `POST api/audio/speak` (provider edge) produced
  speech for "The quick brown fox jumps over the lazy dog.", and feeding that audio back to
  `api/audio/transcribe` returned the sentence verbatim (`provider: local`). Silence returns
  `{"ok":true,"transcript":""}` — the NoSpeech case, not an error.
- Unit tests: 21/21 green — ListenerTest (6), WavAudioTest (2), DictationTakeTest (13).
- NOT verified on device: a real timed key hold. `adb shell input keyevent --longpress` does not
  produce DOWN…UP with a gap, and `sendevent /dev/input/event1` is Permission denied on this AVD, so
  the hold→listen→transcribe path cannot be exercised without the physical phone. This is the one
  thing alpha6 must be judged on, with the microphone.
- Also unverified here: the diagnostics readout — the long-press on the status line did not surface a
  panel in two attempts on the AVD.

## 2026-11-27 — task group 14: M1.5 voice panel and SDK composer

- Owner report is the baseline: alpha6 dictation works on the physical phone. This change adds
  visibility and review, not a replacement recognizer or a repeat of that device probe.
- `Listener.level` exposes normalized chunk RMS with 0.35 exponential smoothing, computed in the
  existing PCM conversion loop. `Listener.partial` exposes DictationTake's stitched completed
  windows. Both reset per take/cancel; session-locked writes reject cancelled or replaced work.
  Partial text starts after a window decodes (the existing 28-second input), not word-by-word.
- Home and Thread replace everything below the SDK top bar with LISTENING, stitched text,
  thirteen thick monochrome bars sampled every 90 ms, oldest left, and the requested hint
  `let go to send · turn the wheel to cancel`. The waveform uses display gain/square-root scaling;
  the underlying RMS remains 0..1. Release freezes the meter and shows `transcribing…`.
  The editor uses the same takeover without disposing its text state or wheel binding.
- Turn-while-held cancels immediately and leaves the prior draft unchanged. Release on every
  text-entry surface finishes into the draft for review; it never submits a gateway prompt.
  This deliberately supersedes M1.4 editor send-and-close despite the requested legacy hint text.
- The SDK `LightTextField` is labelled `Message`, with `hold the wheel to talk` as placeholder.
  It opens the multiline SDK `LightTextInputEditor`; SAVE/Back/wheel click preserve the draft,
  close the editor and focus the adjacent Send action. Send becomes Queue during a running turn.
  The shared controller retains independent per-session drafts across navigation. Obsolete
  synthetic Compose/Send transcript rows and voice send-and-close callbacks were removed.
- Unknown model status now omits the model row. Resolved nonblank session models still display.
  Tool metadata and Menu About are **107 / 0.2.3**, package `dev.loosewire.hermeslight`.
- Local/development lane on the existing owner SDK `main` at
  `251237ef3778c907c5dbd89e84685532c9d99a28`; its pre-existing dirty files were not changed.
  No adb/emulator, install, upload, signing, release publication or push was performed.
  Live capture/dashboard and 411×472dp sunlight/readability/wheel acceptance remain unverified;
  14.2/14.3 retain their visual acceptance boxes and accepted specs are not synced.
- The canonical Obsidian vault remains unavailable: both `Home.md` and `Projects/Projects.md`
  were missing under `/home/gav/Documents/obsidian-vault`. Repository evidence records the
  durable change; a safe intended vault update is retained at `local://hermes-light-m15-vault.md`.

### Verification after the last code edit

With the requested Android SDK, JDK 21 on PATH and both Java installation paths:

```sh
./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain
```

**BUILD SUCCESSFUL in 10s; 202 tests across 24 files, zero failures/errors/skips.**
The initial run passed tests and assembly but found ktlint chain wrapping and missing declaration
spacing; the final four-gate run passed after those formatting-only corrections. Added coverage
checks exact RMS smoothing/reset, stitched partials before release, stale telemetry after restart,
panel phase mapping, no implicit dictation submit, multiline draft retention through navigation/
backgrounding, independent session drafts, and wheel traversal through the new field/Send actions.

Artifact: `tool/build/outputs/apk/debug/tool-debug.apk`.
Existing SDK publication/compile-SDK warnings and fake-microphone Java-Object style warnings remain.

## 2026-11-27 — task group 15: M1.6 stale-running queued-send recovery

- Owner's signed 107 / 0.2.3 AVD report is the baseline; it was not reproduced again.
- Thread open, foreground reconnect/resume, attention refresh and send preflight reconcile
  `running` and the live set with `session.active_list` after runtime resume. A valid idle snapshot
  clears stale running/caret state; newer gateway activity wins snapshot races. Missing/malformed
  snapshots cannot authorize an idle send. Remembered `conversation.running` is no longer ORed
  into the decision to queue a socket submission.
- Queued submissions receive five-second checks of gateway activity and fresh transcript evidence.
  A genuinely busy queue remains monitored. Start/transcript evidence prevents promotion.
  An idle, unstarted queue must have its original admission explicitly cancelled before exactly
  one direct submit of the same local row. This cancellation is necessary: merely sending another
  copy with `queued: false` could leave both copies executable.
- A promoted prompt that is refused or provably cannot start reads `could not start — retry` and
  uses the existing retry action. Acceptance alone is not rendered as transcript delivery.
  Unknown transport outcomes, missing admission receipts, failed polls and unconfirmed
  cancellations remain visibly unknown and do not offer blind resend. That uncertainty is distinct
  from a proven retryable start failure.
- Promotion state survives foreground changes; paused monitors resume without another direct send.
  No SDK widgets, voice capture/hold behavior, dependencies, package id or version were changed:
  **dev.loosewire.hermeslight, 107 / 0.2.3**.
- Local/development lane on owner SDK `main` at
  `251237ef3778c907c5dbd89e84685532c9d99a28`; its pre-existing dirty files were untouched.
  No adb/emulator, installation, signing, upload, publication or push. Actual LP3/live-dashboard
  acceptance remains unverified; accepted specs are not synced in this run.
- Canonical vault unavailable (`Home.md` missing and `Projects/` absent under
  `/home/gav/Documents/obsidian-vault`). Intended durable update: `local://hermes-light-m16-vault.md`.

### Verification after the last code edit

With the requested Android SDK, JDK 21 on PATH and both Java installation paths:

```sh
./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain
```

**BUILD SUCCESSFUL in 10s; 226 tests across 26 files, zero failures/errors/skips.**
The initial run passed tests, color guard and assembly but found ktlint wrapping; all four
gates passed after the formatting-only corrections. Regression coverage includes stale-running
open reconciliation followed by a single direct socket submission, runtime alias activity,
snapshot/event ordering, invalid snapshots, idle-queue cancellation and one direct promotion,
busy queues, start/cancel races, missing receipts, unknown outcomes, retry-row rendering,
promoted start failure, late requeue, foreground rearm, and cancellation while awaiting the
direct-submit receipt. An interrupted in-flight submit remains unknown, never blindly retryable.

Artifact: `tool/build/outputs/apk/debug/tool-debug.apk`.
Existing SDK publication/compile-SDK warnings remain. No device or official-builder acceptance
is inferred from these local gates.

## 2026-11-27 — task group 16: M1.7 new chats and explicit 4090 recovery

- Owner's device rejection (`prompt.submit`, code 4090) is the baseline; it was not reproduced.
- Gateway creation sends `session.create` with `source: "desktop"` and optional title/profile.
  A nonblank returned `session_id` is required; creation seeds stored/runtime identity with that id,
  opens an empty thread and focuses Message without descendant lookup or session resume.
- Sessions now exposes an SDK New session row to touch and wheel. Creation is single-flight,
  visibly pending and never automatically retried. Failure leaves the selected thread/drafts intact.
- A rejected 4090 prompt reads `open in another window`, with `start a new chat here` instead of
  Retry. Only that explicit action creates and opens another thread and resubmits the original
  text, including multiline/edge whitespace. While creating, the old row/text stay visible and the
  action is disabled. Creation failure/cancellation restores the action; subsequent submit failure
  retains the text in the new thread. Queued-to-direct 4090 rejections use the same recovery path.
- Other rejection codes retain ordinary Retry. Unknown transport outcomes are not reclassified as
  ownership conflicts. Create/submit acknowledgement still does not claim delivery or completion;
  replies and terminal state continue to come from gateway events/transcript evidence.
- Added protocol, identity-cache, recovery, text-retention, double-activation/cancellation and
  ordinary-rejection regression coverage. Change specs/roadmap updated; accepted specs not synced
  pending LP3/live-dashboard acceptance.
- Local/development lane on owner SDK `main` at
  `251237ef3778c907c5dbd89e84685532c9d99a28`; pre-existing SDK edits were untouched.
  Package/version unchanged: **dev.loosewire.hermeslight, 107 / 0.2.3**.
  No adb/emulator, installation, upload, publication or push. No visual/hardware/live-dashboard
  proof is claimed; the user forbids the runtime needed to measure the actual LP3 surface.
- Canonical vault remains unavailable (`Home.md` missing and `Projects/` absent under
  `/home/gav/Documents/obsidian-vault`). Intended durable update: `local://hermes-light-m17-vault.md`.

### Verification after the last code edit

With the requested Android SDK, JDK 21 on PATH and both Java installation paths:

```sh
./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain
```

**BUILD SUCCESSFUL in 9s.** All four gates passed after the last code edit. Initial runs passed
unit tests, color guard and APK assembly but found test-only ktlint chain/line wrapping; those
formatting issues were corrected before the final run. Existing SDK publication/compile-SDK and
redundant-else warnings remain. No device or official-builder acceptance is inferred.

Artifact: `tool/build/outputs/apk/debug/tool-debug.apk`.
