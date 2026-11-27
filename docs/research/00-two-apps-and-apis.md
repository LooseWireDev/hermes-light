# The two apps, and what Hermes Light takes from each

Research notes, 2026-11-26. Sources: `~/Projects/BrightHermes` (gi-os, owner), `~/Projects/light/hermes-mobile`
(fork of Hy4ri/hermes-mobile, Apache-2.0), `~/Projects/light-sdk` (Light SDK). Numbers measured in the
trees on that date.

## BrightHermes — the body (24 Kotlin files, ~3.6k LOC)

Layout: `ui/HomeScreen.kt` is the whole product — an **Info Deck** at the top and the **June thread**
below it. Supporting pieces:

| Piece | What it gives us |
| --- | --- |
| `hw/WheelTalk` + `com.gios.light.common.hw.WheelScroll` | Hold-wheel push-to-talk with live dictation preview, "let go to send / turn to cancel"; wheel-driven scrolling with reverse-at-bottom. The best thing in the app. |
| `deck/Deck.kt` + `DeckProvider.kt` + `ui/DeckViews.kt` | Tile model (`label·value·sub·action`, stale-at, HTML widgets), 2-column slot layout, ContentProvider so the deck can surface outside the app. |
| `chat/Protocol.kt` | Frames `Ok/Start/Delta/Tool/Thinking/Done/Error/DeckChanged`; `hello/user/stop`; `Bot(id,name)`; `Message`. This is the shape a phone thread wants — small and honest. |
| `net/Api.kt`, `net/Socket.kt` | Thin HTTP + WS client against the owner's *bridge gateway* (`/deck`, `/thread?bot=`, ingest). Works, but the bridge is a second surface to keep alive. |
| `ui/SetupScreen.kt`, `Prefs.kt`, `HermesViewModel.kt` | Endpoint setup, persistence, state hoisting. |
| `ui/EditDeckScreen.kt`, `LockCard.kt` | Deck editing and the lock card. |

Weaknesses it carries: **one thread**, no session history, no bots surface beyond a fixed bot id, no
cron, no approvals, no reasoning/tool detail.

## Hermes Mobile fork — the brain (717 Kotlin files, ~156k LOC)

A full Material3 client for the **Hermes dashboard** (loopback/token or gated basic-auth). The parts
worth lifting are contract knowledge, not UI:

- **Auth**: `GET /api/status` (`auth_required`), `GET /api/auth/providers`, `POST /auth/password-login`,
  or loopback **token mode** (SPA embeds `__HERMES_SESSION_TOKEN__`); WS credential via
  `POST /api/auth/ws-ticket` when gated.
- **REST** (sessions family): `api/sessions` (list, `limit/offset/order/exclude_sources/profile`),
  `api/sessions/{id}`, `api/sessions/{id}/messages` (`limit/offset/order=latest`, `include_compacted`),
  `.../messages/around`, `.../timeline`, `.../mutation-snapshot`, `api/sessions/search`,
  `api/sessions/stats`, `DELETE api/sessions/{id}`, `DELETE api/sessions/empty`.
- **Cron**: `api/cron/jobs` (+ `{id}` detail, `trigger`, `pause`, `resume`, `cancel`),
  `api/cron/blueprints`, `api/cron/delivery-targets`.
- **Voice helpers on the gateway**: `POST api/audio/transcribe`, `POST api/audio/speak` (usable as a
  fallback when on-device dictation is unavailable).
- **WS gateway**: JSON-RPC over `wss://…/…?token=…` (or `?ticket=…`); server-push events the phone
  must understand: `message.start|interim|delta|token|complete|done`, `reasoning(.available)`,
  `approval.request|pending|received`, `clarify.request|expire|settled`, `process.*`, `done`,
  `error`, plus `session`/`queue` events. Methods used for interaction: `approval.respond`,
  `clarify.respond`, `command.dispatch`, `connection.respond`, `client.capabilities|activity`,
  `gateway.ping`.
- The authoritative contract snapshot ships in that repo (`chore: update gateway contract snapshot`).

## What Hermes Light is, therefore

A **third body of code** that keeps BrightHermes' shell and wheel/voice craft, and replaces its
bridge-gateway data layer with the dashboard REST/WS contract Hermes Mobile already knows. Nothing
is forked forward: BrightHermes stays as it is (glanceable single-thread), the Hermes Mobile fork
stays as the power client (kanban, files, logs, admin), and Hermes Light becomes the daily driver.

Reuse policy: BrightHermes code (owner's) may be copied directly; Hermes Mobile code is Apache-2.0 —
copy with headers and a `NOTICE` entry, and prefer re-implementing the thin parts (`ApiClient`,
`WsTransport`) over dragging its Material3-bound UI.
