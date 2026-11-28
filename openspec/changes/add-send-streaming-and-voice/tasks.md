# Tasks — add-send-streaming-and-voice (M1)

One commit per group. Gates green at every commit: ktlint, color guard, unit tests, assembleDebug.
Verify on the LP3 AVD before ticking visual items. Never auto-approve; never claim acceptance early.

Implementation note (2026-11-27): code for groups 2–4 and 6 is integrated; visual/live-device
acceptance stays unchecked for the parent (this run must not drive adb/the AVD). Gates run once
after integration, not between edits. See `evidence.md` for protocol corrections and final results.

## 1. Socket transport facts (already researched — do not re-derive)

- Send: `prompt.submit {session_id, text, queued?, clarify_batch?}`
- Cancel a queued prompt: `prompt.cancel`
- Interrupt a running turn: `session.interrupt {session_id}`
- Approvals: `approval.respond {session_id, request_id?, choice, all?, reason?}`
- Clarify: `clarify.respond {session_id, clarify_id, ...}` (check the contract in
  `~/Projects/light/hermes-mobile/app/src/main/java/com/m57/hermescontrol/data/ws/contract/`)
- Server push: `message.start|interim|delta|token|complete|done`, `reasoning(.available)`,
  `approval.request|pending|received`, `clarify.request|expire|settled`, `process.*`, `done`, `error`

## 2. Send

- [ ] 2.1 Composer state: send action (wheel click / release of voice) submits `prompt.submit` for the current session; while a turn runs, submits with `queued: true` and renders the text as a queued row (never as a delivered message).
- [ ] 2.2 Failures surface as a row-level error with retry; a rejected submit restores the text to the composer.
- [ ] 2.3 Stop action (`session.interrupt`) while streaming, and `prompt.cancel` for a queued prompt.
- [x] 2.4 Unit tests for the submit/queue/stop state machine (no Android).

## 3. Streaming render

- [x] 3.1 Event reducer folds `message.start/interim/delta/token/complete/done`, `reasoning`, `process.*`, `done`, `error` into thread state; unknown event types are ignored without crashing.
- [ ] 3.2 Thread renders a streaming assistant row with a subtle caret; `done` replaces it with the final text; `error` shows the message.
- [ ] 3.3 Reconnect mid-stream: state converges on the REST transcript after reconnect, with no duplicated rows.

## 4. Approvals and clarify

- [ ] 4.1 `approval.request` renders a blocking row set in the thread: the question line, then `Yes` / `No` / `Always` rows (Always only when the payload allows `all`), focus starts on `Yes`. Activation sends `approval.respond` with `request_id` and shows `sending…` until `approval.received` or the request settles.
- [ ] 4.2 `clarify.request` renders its options as rows; activation sends `clarify.respond`; expiry (`clarify.expire`) replaces rows with "expired — answer on desktop".
- [ ] 4.3 Nothing auto-answers when the screen is not open; a pending approval survives navigation (state hoisted) and re-renders.
- [x] 4.4 Unit tests: approval choice mapping, `all` gating, settled/expired transitions.

## 5. Voice (port from BrightHermes, verbatim where possible)

- [ ] 5.1 Copy `hw/WheelTalk.kt`, `voice/DictationTake.kt`, `voice/Listener.kt` from `~/Projects/BrightHermes/app/src/main/kotlin/com/gios/brighthermes/` into `dev.loosewire.hermeslight.voice` (+ `hw`), with header attribution "ported from BrightHermes (owner)".
- [ ] 5.2 Add `android.permission.RECORD_AUDIO` to `tool/lighttool.toml` and the runtime permission request path; denied permission shows a specific line with a settings action.
- [ ] 5.3 Wire hold-to-talk on the thread: hold (wheel or the on-screen PTT line) starts capture; release sends the stitched transcript via `prompt.submit`; turn cancels; panel inverts while listening.
- [x] 5.4 Long-take acceptance: a synthetic 3-minute take (feed DictationTake in a unit test with overlapping windows) produces one continuous transcript with no duplicated seam words.
- [ ] 5.5 Voice works while a reply is streaming (send path must queue like typed text).

Voice blocker: current owner `Listener` requires unallowlisted sherpa-onnx, blocked Context access,
and absent Parakeet assets; it is not a SpeechRecognizer loop. The verbatim stitcher is capped at
300 seconds. Owner must choose an approved recognizer/SDK route and cap policy. SDK permission
request is wired, but no SDK permission-settings action exists. No capture/send/inversion is
claimed; 5.1–5.3 and 5.5 remain unchecked. Details: `evidence.md`.

## 6. Live strip + stale session

- [ ] 6.1 Home strip: running turns and pending approvals from live state → one line, zero height when idle; activation opens the approval (or the running session).
- [ ] 6.2 Remembered session that 404s: clear silently, land on Sessions, no raw error row.

## 7. Release

- [x] 7.1 Bump `lighttool.toml` to versionCode 103 / versionName 0.2.0.
- [ ] 7.2 Gates green; assembleRelease; the parent signs and publishes alpha4. Append evidence note.

Final gates: `ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug
:tool:assembleRelease` — BUILD SUCCESSFUL in 8s; 140 tests, zero failures/errors/skips.
Unsigned release assembled; parent signing/publication and device acceptance are still outstanding.
Task 4.1's `approval.received` settlement wording is superseded by the safety correction in evidence:
it is display acknowledgement only, not resolution.

## 8. Owner-facing release notes text (for the parent)

Do not publish the voice/unbounded claim below until the owner decisions and device acceptance
are resolved; the current verbatim source is capped at 300 seconds and has no accepted runtime port.

Alpha4 bullets: you can send, watch it stream, stop it, answer approvals from your pocket, and hold
the wheel to talk — as long as you like, because dictation is chunked and stitched.

## 9. M1.1 — live-id resolution (device blocker)

- [x] 9.1 Before `prompt.submit`, resolve the session's current id via `GET api/sessions/{id}/latest-descendant`; cache per session; on `4001 session not found` re-resolve once and retry, then surface a specific error.
- [x] 9.2 Audit every session-id path (`messages`, `messages/around`, `latest-descendant`, `session.interrupt`, approval/clarify payloads) for URL-encoding of ids containing `/`.
- [x] 9.3 Sessions screen: replace the stale "sending arrives in M1" subtitle with honest copy (`New session` with today's behavior), or wire `session.create` if the method is available and trivial.
- [x] 9.4 Unit tests for the resolve-then-submit path (including one 4001 retry) and id encoding.

## 10. M1.2 — resume for a runtime id (device blocker #2)

- [x] 10.1 When a thread opens (and before the first submit), call `session.resume {session_id, source: "desktop"}` and use the returned `session_id` as the runtime id for submit and for event correlation; cache it per stored id and refresh on 4001.
- [x] 10.2 Keep `latest-descendant` as the tip resolver (it returns the stored tip, not the runtime id) — document that order in the code: descendant → resume → submit.
- [x] 10.3 Log RPC failures (method, code, message — never tokens) at debug level so device failures are diagnosable from logcat.
- [x] 10.4 Unit tests: resume-then-submit happy path; 4001 → resume → retry; resume failure surfaces a specific error.

## 11. M1.3 — terminal event never clears the live state (device defect from the M1.2 probe)

Found by device probe on `mealforge_lpi3_ui_test_v1` (2026-11-27). Accepted path works end to end:
descendant → resume → submit delivered the prompt, deltas streamed, and the server persisted the turn
on the session's own model. The defect is the *end* of the turn.

- [ ] 11.1 Apply the run-completion event to live UI state: when the assistant turn ends, clear the
      streaming caret, drop the `· Stop` affordance, and clear the session's `● live` marker without
      requiring an app restart. Reproduction: submit a prompt, wait for the server's
      `tui turn finished: status=complete`, observe the UI still streaming minutes later.
- [ ] 11.2 Correlate terminal events by every id the turn was known by (stored id, resumed runtime id
      and any runtime alias), so a completion delivered against an alias still closes the run.
- [ ] 11.3 Extend the debug log beyond RPC failures: emit event routing (event name, stored id,
      runtime id, whether it was applied) at debug level so a missed terminal event is readable from
      logcat instead of inferred. This probe left no app-side trace because 10.3 logs rejections only.
- [ ] 11.4 Unit tests: completion applied on stored id, on runtime id, and on a late alias; a second
      completion after close is a no-op; live state is derived from applied events, not sticky flags.
- [ ] 11.5 Device re-probe: accepted turn ends with no caret and no Stop while the app stays open; then
      exercise the rejection path (submit into a session deleted server-side → single re-resolve, honest
      error row, draft restored).

## 11. Wheel + voice wiring (device blocker #3 — hold does nothing)

Root cause: `hw/WheelTalk.kt` was ported but nothing instantiated it and no screen claimed a key,
so the SDK forwarded every notch/click to LightOS. SDK key ownership is now implemented; full
voice remains blocked by the absent recognizer port/assets documented in `evidence.md`.

- [x] 11.1 Implement the SDK key handler on the thread (and Sessions): claim `WHEEL_CCW`, `WHEEL_CW`, `WHEEL_CLICK`, `FOCUS`, `CAMERA` on DOWN and UP, return true, and feed them into one `WheelTalk` instance hoisted in the screen's view model so holds survive recomposition.
- [ ] 11.2 Wire the three actions: click = send composed text / stop running turn; hold = start take, release = submit stitched transcript via `prompt.submit`; turn while held = cancel take; bare turn = scroll the thread.
- [x] 11.3 Request `RECORD_AUDIO` on first voice use; on denial show one line naming LightOS Settings; never capture before the grant.
- [ ] 11.4 Diagnostics readout (long-press the thread status line): clicks seen, turns seen, mic granted, whether BrightControl is installed, and one suggested fix line when clicks are zero.
- [x] 11.5 Unit tests: click vs hold split at HOLD_MS, turn-while-held cancels, key handler claims only the wheel keys and passes Back/Home through.
- [x] 11.6 Document in AGENTS.md and the release notes: BrightControl's per-app rule for `dev.loosewire.hermeslight` must be **Off** (hands-off) so the click and camera reach the app; `com.thelightphone.*` and `com.gios.*` are hands-off by default, ours is not.

## 12. Menu page v2 (owner request: back, but filled out)

- [ ] 12.1 Home top bar: single **Menu** action (History/Bots actions removed).
- [ ] 12.2 Menu page: status block (connected + model + session count), rows History / Bots / Cron / Settings / About, each with a live count where one exists (`Cron · 0 jobs`), Light row style.
- [ ] 12.3 Menu lists the three most recent sessions below the rows; each opens its thread; Back walks Thread → Sessions → Menu → Home.
- [x] 12.4 Version bump to 0.2.1 (code 105) and gates green.

Implementation (2026-11-27): 11.4 and 12.1–12.3 are wired but retain their visual acceptance
boxes: this run must not use adb/emulator. Thread click sends the draft/stops the run, decisions
remain wheel-actionable, and bare turns scroll. 11.2 is incomplete: this checkout has no Listener,
sherpa-onnx dependency or Parakeet assets; a granted hold says so and captures/sends nothing.
No server-side audio upload was substituted for the on-device pipeline.

## 13. Voice engine (owner report: "there is no voice engine")

Facts: LightOS ships **no system speech recognizer**; BrightHermes transcribes on-device with
sherpa-onnx + NVIDIA Parakeet TDT 110M int8 (`app/libs/sherpa-onnx-1.13.6-arm64.aar`, ~120 MB model
fetched at build). Our M1 run ported only `DictationTake` (the stitcher) — **no capture, no engine**
exists, so a hold has nothing to run. The dashboard also exposes `POST /api/audio/transcribe`
`{data_url, mime_type}` (base64 audio in, `{transcript}` out) — the desktop client's voice path.

- [x] 13.1 `voice/Listener.kt`: AudioRecord (VOICE_RECOGNITION, 16 kHz mono, PCM16), windowed capture driven by `DictationTake`, states Listening / Transcribing / NoSpeech / Error, and a single commit point on release.
- [x] 13.2 Transcription via the dashboard: each window → WAV → base64 data URL → `POST api/audio/transcribe` with the session's auth → transcript; stitch windows with the existing anchor logic. Empty transcript is NoSpeech, not an error.
- [x] 13.3 Honest states in the UI: `listening` while held, `transcribing…` after release, then the text lands in the composer (not auto-send unless the release gesture says send) — and `no speech` when the server returns nothing.
- [x] 13.4 No network → specific line ("transcription needs the dashboard"), take discarded, nothing sent.
- [x] 13.5 Unit tests: WAV header correctness for 16 kHz mono PCM16, base64 data URL shape, window stitching through DictationTake with 3 overlapping windows, empty-transcript → NoSpeech.
- [x] 13.6 Leave on-device sherpa as a future option (offline/latency) documented in design.md, with the arm64 + ~120 MB cost written down rather than discovered.

M1.4 implementation and requested local gates complete at **106 / 0.2.2**. These checkboxes
record implementation, not LP3 visual/hardware acceptance: this task explicitly forbids adb/
emulator use. No live microphone/dashboard transcription is claimed. See `design.md` and
`evidence.md`; original timestamped stitching remains, with conservative text-only anchors
for dashboard responses. Earlier missing-engine notes above describe the pre-M1.4 builds.

## 14. Voice panel + real composer (owner feedback on alpha6)

Owner: dictation works on the phone; wants a visible listening state (BrightHermes' full-area panel
with waveform, then a compose step) and a proper Light SDK text field instead of `· Compose`.

- [x] 14.1 Listener exposes `level: StateFlow<Float>` (RMS of the live chunk, 0..1) and `partial: StateFlow<String>` (windows stitched so far).
- [ ] 14.2 Listening panel: full content-area takeover while recording — 13-bar waveform (90 ms steps, oldest left), live partial text, hint `let go to send · turn the wheel to cancel`, thread hidden behind it; `transcribing…` state on release, then the final text lands in the composer.
- [ ] 14.3 Composer: `LightTextField` (label `Message`, placeholder naming the wheel gesture) opening `LightTextInputEditor`, a send action beside it with focus state, multi-line text, and a draft that survives navigation.
- [x] 14.4 Status: never print `Model unavailable`; show the model when resolved, omit the fragment otherwise.
- [x] 14.5 Tests for the panel's state mapping (idle → listening → transcribing → composer) and the draft retention. Version 0.2.3 / code 107.

M1.5 implementation and all four requested local gates are complete at **107 / 0.2.3**.
14.2 and 14.3 remain unchecked only for visual/hardware acceptance: this task explicitly forbids
adb/emulator use. Home, Thread and the SDK editor now show the takeover panel; all releases
finish into a reviewable draft, and editor SAVE returns with Send focused. The synthetic Compose/
Send transcript rows and the editor send-on-release path are removed. Drafts remain per-session
in the shared controller. See `evidence.md` for the 202-test result and remaining device boundary.

## 15. Queued prompt stuck when the running flag is stale (found on the AVD)

Device trace (0.2.3/107): a send from the new composer rendered the button as QUEUE, cleared the
composer, and the prompt never appeared in the dashboard transcript — `prompt.submit` went out with
`queued: true` because `conversation.running` was true for a session that was actually idle. A queued
prompt on an idle session never starts, so the send is silently lost.

- [ ] 15.1 Running state must come from the gateway, not from a remembered flag: when a thread opens (and on resume), reconcile `running` against the session's live state before the first submit.
- [ ] 15.2 After submitting with `queued = true`, verify the turn actually starts (event or session poll) within ~5s; if the session is idle and the prompt has not started, resubmit once with `queued = false` and mark the row accordingly.
- [ ] 15.3 A queued row that cannot start surfaces `could not start — retry` instead of sitting silently.
- [ ] 15.4 Unit tests: stale-running reconciliation, queued→direct promotion, and no double submit.
