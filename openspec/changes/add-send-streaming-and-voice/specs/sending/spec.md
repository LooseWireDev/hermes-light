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

### Requirement: Resume stored sessions before sending

Opening a thread SHALL resolve `latest-descendant` to the stored transcript tip, then call
`session.resume {session_id, source: "desktop"}` to obtain a runtime `session_id`. The runtime id
SHALL be cached per stored session within the authenticated connection and used for submit,
interrupt and incoming-event correlation. A first submit SHALL also ensure resume has completed.
No missing or failed resume SHALL fall back to submitting the stored id.

#### Scenario: Runtime differs from stored tip

- **WHEN** resume returns a runtime id different from the stored tip
- **THEN** submit and interrupt use that runtime id, while events update the original stored thread

#### Scenario: Runtime no longer exists

- **WHEN** submit is explicitly rejected with `4001 session not found`
- **THEN** resolve the stored tip and resume again, retry the unchanged prompt once, and surface a specific error if it is still refused
- **AND** never resend a prompt after a timeout or lost-socket outcome

#### Scenario: Resume fails

- **WHEN** resume fails before submit
- **THEN** show a specific resume error and retain the draft as unsent, even if the resume outcome is unknown

RPC failures SHALL be logged at debug level with method, code and message, without credentials,
request parameters or text bodies.

### Requirement: Stop and cancel

A visible stop action SHALL send `session.interrupt` for a running turn and `prompt.cancel` for a
queued prompt, reflecting the gateway's acknowledgement rather than assuming it.

#### Scenario: Stop a turn

- **WHEN** the user activates stop during streaming
- **THEN** the app sends `session.interrupt`, the caret stops, and the final state matches the gateway's `done`/`error`
