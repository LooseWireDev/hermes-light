# Proposal — Hermes Light foundation

## Why

Two clients exist and neither fits the daily use of a Light Phone III:

- **BrightHermes** is beautifully sized for the phone — Light SDK widgets, wheel scrolling, hold-wheel
  push-to-talk — but it has **one thread**, no history, no bots surface, no cron, no approvals; its
  data layer speaks the owner's bridge gateway rather than the Hermes dashboard itself.
- **Hermes Mobile** (forked, compacted) has the whole dashboard — sessions, history, bots, cron,
  kanban, approvals — but it is a 156k-LOC Material3 app compressed into a small screen. Even after
  the LP3 compact work it is a phone app made to fit, not a Light Phone app.

The phone wants BrightHermes' body with Hermes Mobile's brain. That is a new, small application:
**Hermes Light**.

## What Changes

Create a new repository and a new application:

- **Shell**: `SealedLightActivity` on the Light SDK, AMOLED black, a two-zone Home — Info Deck above,
  June thread below — following BrightHermes' layout and wheel craft.
- **Data plane**: the Hermes dashboard REST contract (sessions, messages, cron) plus its JSON-RPC
  WebSocket gateway (message streaming, approvals, clarify, processes), with the two auth modes the
  dashboard supports (loopback token, gated basic-auth + ws-ticket). No bridge gateway.
- **Session triage**: a Light-native session list (title, age, live dot) that switches the thread;
  new session action; bots listed and selectable.
- **Read-first thread**: full transcript scrolling with wheel, paged history, honest status
  (connecting/streaming/error), reasoning and tool activity as one-line rows.
- **Setup**: endpoint entry, sign-in (password) or pasted token, connection test, persisted profile.

M0 deliberately excludes: sending, approvals interaction, voice, deck data tiles (they follow in M1–M2
per `openspec/roadmap.md`).

## Capabilities

### New Capabilities

- `connectivity`: endpoint + auth (token or password), session/wsticket lifecycle, honest reconnect
  and error states.
- `session-triage`: session list with live status, switching the thread, bot/profile selection.
- `phone-thread`: read-only transcript for M0 (scrollback, paging, streaming preview), structured
  for M1 send/approval additions.
- `deck-surface`: the Home two-zone layout with a placeholder deck in M0 (real tiles in M2).

### Modified Capabilities

None — new repository, no prior behavior.

## Impact

- New repo `LooseWireDev/hermes-light`, package `dev.loosewire.hermeslight`, module `:tool`, Kotlin +
  Compose on the Light SDK; no Material3 chrome.
- Operates against the existing Hermes dashboard (the owner's, over Tailscale; loopback for tests).
- Adds no server-side changes. Attaches an Apache-2.0 NOTICE for derived protocol handling.
- Six screens max; ~10k LOC ceiling for M1–M3.
