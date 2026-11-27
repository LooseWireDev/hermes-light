# Connectivity Spec Delta

## Purpose

Reach a Hermes dashboard from the phone with an honest connection state, in either auth mode, and
never hang or lie about it.

## ADDED Requirements

### Requirement: Endpoint and auth discovery

The app SHALL probe `GET api/status` to learn whether the dashboard requires auth, and SHALL support
exactly two credential modes: token mode (loopback gate down — the token is pasted or extracted from
the served SPA) and password mode (gated — `POST /auth/password-login` establishing the session, plus
`POST api/auth/ws-ticket` for the socket). An unreachable or invalid endpoint MUST surface an error
within 25 seconds and MUST leave the form usable for retry.

#### Scenario: Loopback token mode

- **WHEN** the dashboard reports `auth_required: false`
- **THEN** the app connects without any credential prompt beyond the token it obtained itself, and shows `connected`

#### Scenario: Unreachable endpoint

- **WHEN** the entered endpoint does not answer (refused, wrong port, wrong scheme)
- **THEN** within 25 seconds the app shows a specific error ("dashboard unreachable — check address") and the connect action is enabled again

#### Scenario: Wrong password

- **WHEN** the password is rejected
- **THEN** the error is shown without clearing the typed URL, and no partial credentials are persisted

### Requirement: Connection state is always visible

The app SHALL render one status line on every screen describing the current link: connecting,
connected, reconnecting (attempt n), or the error text. A dropped socket MUST be shown as such and
MUST be retried with backoff while the app is foregrounded.

#### Scenario: Dashboard killed mid-session

- **WHEN** the dashboard becomes unreachable while a thread is open
- **THEN** the status line changes to reconnecting within the socket timeout, the thread keeps its content, and reconnection restores live updates without duplicating messages

### Requirement: Credentials at rest

Stored credentials SHALL be encrypted with an Android Keystore–backed key; the app MUST NOT write
tokens or passwords to plain preferences or logs.

#### Scenario: Cold start

- **WHEN** the app is launched after a successful setup
- **THEN** it reconnects with the stored profile without any prompt, and a cleared app data store lands on Setup instead of an empty thread
