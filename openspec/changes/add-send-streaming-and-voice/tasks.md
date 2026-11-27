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

- [ ] 9.1 Before `prompt.submit`, resolve the session's current id via `GET api/sessions/{id}/latest-descendant`; cache per session; on `4001 session not found` re-resolve once and retry, then surface a specific error.
- [ ] 9.2 Audit every session-id path (`messages`, `messages/around`, `latest-descendant`, `session.interrupt`, approval/clarify payloads) for URL-encoding of ids containing `/`.
- [ ] 9.3 Sessions screen: replace the stale "sending arrives in M1" subtitle with honest copy (`New session` with today's behavior), or wire `session.create` if the method is available and trivial.
- [ ] 9.4 Unit tests for the resolve-then-submit path (including one 4001 retry) and id encoding.
