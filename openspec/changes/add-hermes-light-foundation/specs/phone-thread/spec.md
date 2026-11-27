# Phone Thread Spec Delta

## Purpose

Read any session's transcript on the phone, wheel-driven, in the SDK's design language, with truthful
state. M0 is read-only; sending, streaming and approvals are added in M1 without changing this
surface's contract.

## ADDED Requirements

### Requirement: Transcript rendering

The thread SHALL render a session's messages bottom-anchored, oldest-reachable by wheel-up paging in
50-message windows (`api/sessions/{id}/messages`, `.../messages/around`), with minimal markdown
support: bold, inline code, fenced code blocks and underlined links. Tool calls and reasoning SHALL
collapse to single rows that can be expanded.

#### Scenario: Long session

- **WHEN** a session has hundreds of messages
- **THEN** the newest 50 render immediately, wheel-up requests the previous window without losing the current anchor, and the bottom remains reachable with a single wheel reversal

#### Scenario: Rich content degrades honestly

- **WHEN** a message contains images, attachments or unsupported markdown
- **THEN** the row shows a labeled placeholder or plain text rather than misleading rendering, and never blanks the neighbouring rows

### Requirement: Read-only honesty (M0)

In M0 the thread MUST NOT send turns, and MUST NOT render an approval or clarify as something the
phone can answer: such frames appear as a non-interactive "awaiting desktop" row.

#### Scenario: Approval arrives while read-only

- **WHEN** the socket reports `approval.request` in an M0 build
- **THEN** the thread shows a static row naming the approval and its origin, with no action affordance

### Requirement: Every screen is wheel-operable

All navigation and list interaction SHALL work with wheel turn, click, hold and back alone; touch and
keyboard are additive, never required.

#### Scenario: Wheel-only journey

- **WHEN** a user starts at Home with no session open
- **THEN** wheel-up opens Sessions, click opens a session, back returns to Sessions, back returns to Home — and every step shows the same status line
