# Proposal — Send, streaming, approvals and voice (M1)

## Why

M0 proves the phone can read the dashboard. M1 makes it a client: you can send a turn, watch it
stream, unblock the agent from the phone when it asks, and talk to it — the last one is why the
owner uses this thing outdoors, in sunlight, with one hand.

## What Changes

- **Sending**: `prompt.submit {session_id, text}` over the JSON-RPC socket with the same honesty
  rules as the regular client: nothing is rendered as sent before the gateway accepts it; queued
  sends (`queued: true`) are shown as queued; `prompt.cancel` and `session.interrupt` are wired to
  a stop action.
- **Streaming render**: `message.start/interim/delta/token/complete/done`, `reasoning(.available)`,
  `process.*` fold into the thread with a live caret; tool and reasoning rows stay one-liners.
- **Approvals**: `approval.request/pending` renders a **blocking Light row set** (Yes / No / Always
  for this) in the thread, sending `approval.respond {session_id, request_id, choice, all, reason}`;
  `clarify.request` renders its options as rows sending `clarify.respond`. Nothing is ever
  auto-answered; an expiry or settled frame replaces the rows with the outcome.
- **Voice**: the BrightHermes pipeline is ported — `WheelTalk` (hold = talk, release = send, turn =
  cancel, panel inverts for sunlight), `DictationTake` (overlapping windows stitched by word anchors
  so a take can be minutes long), `Listener` (bounded previews, single commit point). Verified
  transcript goes through the same `prompt.submit` path as typed text.
- **Live strip**: the Home status strip is wired to real state — running turns and pending approvals
  produce one line; nothing pending renders zero height (already in the spec).
- **Stale session fix**: a remembered session id that 404s is cleared silently and the app lands on
  Sessions without a raw error row.

## Capabilities

### New Capabilities

- `sending`: prompt submit/cancel/interrupt over the gateway with honest acceptance states.
- `approvals`: blocking approval and clarify rows with explicit responses.
- `voice-input`: moved from the M0 change (hold-to-talk with chunked unbounded dictation).
- `live-strip`: Home strip fed by real run/approval state.

### Modified Capabilities

- `phone-thread`: gains streaming, sending and inline approvals/clarify (its M0 read-only rule is
  replaced).

## Impact

- `tool/src/main/kotlin/dev/loosewire/hermeslight/`: new `voice/` package (ported), `net/` gains
  `prompt.submit`/`cancel`, `session.interrupt`, `approval.respond`, `clarify.respond` and the event
  reducer for `message.*`/`approval.*`/`clarify.*`; `ui/ThreadScreen` gains composer + approval rows
  + caret; `HomeScreen` strip wiring.
- `tool/lighttool.toml`: add `android.permission.RECORD_AUDIO`.
- No server changes. Attribution: voice files are the owner's BrightHermes code; no third-party code.
