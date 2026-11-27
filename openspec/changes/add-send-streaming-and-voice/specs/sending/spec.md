# Sending Spec Delta

## Purpose

Send a turn from the phone and be able to trust what the screen says about it.

## ADDED Requirements

### Requirement: Honest submission

Sending SHALL use `prompt.submit {session_id, text}`. Until the gateway accepts it, the text MUST
appear as pending (never as a delivered user message); if the submit is rejected the text SHALL be
restored to the composer with a visible error. While a turn is running, a send SHALL be submitted
with `queued: true` and rendered as a queued row that clears when the turn picks it up.

#### Scenario: Accepted send

- **WHEN** the user sends text while the session is idle
- **THEN** the row shows as pending, the gateway's `message.start`/transcript replaces it with the delivered message, and no duplicate appears

#### Scenario: Rejected send

- **WHEN** the gateway rejects `prompt.submit`
- **THEN** the pending row disappears, the text returns to the composer, and an error line names the failure

#### Scenario: Send during a running turn

- **WHEN** the user sends while a turn streams
- **THEN** the row renders as queued (distinct from delivered), and it becomes a normal message only when the gateway starts it

### Requirement: Stop and cancel

A visible stop action SHALL send `session.interrupt` for a running turn and `prompt.cancel` for a
queued prompt, reflecting the gateway's acknowledgement rather than assuming it.

#### Scenario: Stop a turn

- **WHEN** the user activates stop during streaming
- **THEN** the app sends `session.interrupt`, the caret stops, and the final state matches the gateway's `done`/`error`
