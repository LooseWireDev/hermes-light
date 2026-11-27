# Credentials Paste Spec Delta

## Purpose

Credentials must be enterable on the phone without OS autofill. The Light SDK's embedded text editor
has no clipboard support (no long-press paste, no keyboard paste key) and exposes no platform text
field for autofill services, so Hermes Light MUST offer clipboard ingestion itself.

## ADDED Requirements

### Requirement: Paste is a first-class action at Setup

Every Setup field that receives non-trivial text (server address, token, username, password) SHALL
offer a Paste action in the Setup list that reads the system clipboard and sets the field value
directly, without opening the SDK text editor. After pasting, the field MUST reflect the new value
(masked for secrets) and the next action (Test connection / Sign in) MUST use it. An empty clipboard
MUST show a specific hint, never a silent no-op.

#### Scenario: Password from a password manager

- **WHEN** the user copies their dashboard password in Bitwarden and activates Paste on the password row
- **THEN** the password row shows a masked value, a "pasted N characters" confirmation, and Sign in proceeds with that value

#### Scenario: Empty clipboard

- **WHEN** Paste is activated with nothing on the clipboard
- **THEN** a hint reads "clipboard is empty" and no state changes

#### Scenario: Whitespace and newlines

- **WHEN** the clipboard text has leading/trailing whitespace or a trailing newline (typical of password managers)
- **THEN** the value is trimmed before use, and the visible length confirmation reflects the trimmed value

### Requirement: The editor is not the only way in

No credential field may be enterable only through the SDK text editor. The Paste action SHALL be
visible without opening the editor, and the screen MUST document the clipboard path in one quiet hint
line under the credential rows.

#### Scenario: Discoverability

- **WHEN** the Setup screen is shown with password mode selected
- **THEN** a hint line states that Bitwarden-copied values can be pasted, and the Paste rows are present for username and password

### Requirement: Autofill limitation is recorded

Because the SDK editor provides no autofill surface, Hermes Light MUST NOT claim autofill support;
AGENTS.md records the limitation and points future work at the SDK (or a native field shim) rather
than inventing a second credential flow.
