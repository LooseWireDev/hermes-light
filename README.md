# Hermes Light

**Your Hermes agent, sized for the Light Phone III.**

A Light SDK–native Android app (Kotlin, Compose, LightOS widgets) that puts the useful half of the
Hermes dashboard on the phone — live sessions, history, bots, cron and approvals — in a form a
thumb-and-wheel device can actually carry.

Hermes Light is not a port. It is a small, purpose-built client:

- **Info Deck on top** — glanceable tiles (running sessions, cron, approvals, unread).
- **Thread below** — read and send in any session, streamed live; **hold the wheel** to talk.
- **Wheel-first** — scroll, page, open, back; no chrome, no chip rows, no sheets that eat the screen.

## Lineage

| | used for |
| --- | --- |
| [gi-os/BrightHermes](https://github.com/gi-os/BrightHermes) | the body: Light SDK shell, wheel widgets, `WheelTalk` hold-to-talk + dictation, deck-tile model, protocol-honest UI patterns. |
| [Hy4ri/hermes-mobile](https://github.com/Hy4ri/hermes-mobile) (Apache-2.0) | the brain: the Hermes dashboard REST/WS contract — sessions, messages, streaming events, approvals, clarify, cron, auth. |
| [lightphone/light-sdk](https://github.com/lightphone/light-sdk) | the design system and app framework (`SealedLightActivity`, `LightText`, `LightTopBar`, wheel helpers). |

Reuse and attribution rules live in `AGENTS.md`; the protocol surface is documented in
`docs/research/`.

## Status

Specification stage. See `openspec/` — the living spec, roadmap and the current change
(`add-hermes-light-foundation`). No application code exists until the foundation spec is approved.
