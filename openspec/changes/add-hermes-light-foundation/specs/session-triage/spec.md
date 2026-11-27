# Session Triage Spec Delta

## Purpose

Make every session on the dashboard reachable and switchable from the phone with the wheel alone.

## ADDED Requirements

### Requirement: Session list

The app SHALL list recent sessions (title, source, age, live indicator) fetched from
`GET api/sessions` with paging, and SHALL switch the thread to the selected session without losing
per-session scroll position or draft state. Sessions excluded from the phone surface (cron) SHALL be
excluded by default, matching the fork's `exclude_sources=cron`.

#### Scenario: Many sessions

- **WHEN** more than 50 sessions exist
- **THEN** the wheel reaches the last rendered row, and turning past it loads the next page; the list never blocks on a fetch that fails silently

#### Scenario: Switching sessions

- **WHEN** the user opens a session, scrolls, and returns to the list
- **THEN** the list shows the same position, and reopening the session restores its scroll position

#### Scenario: Live indicator

- **WHEN** a session is running a turn
- **THEN** its row shows a live mark that clears when the turn completes or errors, sourced from the socket rather than a timer

### Requirement: Bots are selectable

The app SHALL present the available bots/profiles (June, Forge, Chef, Sysadmin and any others present
in the dashboard) as a selectable list, and selecting one SHALL scope the thread to that bot's
sessions. The selected bot MUST persist across launches.

#### Scenario: Bot switching

- **WHEN** the user selects a bot from the Bots screen
- **THEN** the thread and session list reflect that bot's sessions, and the status line keeps reporting the same connection
