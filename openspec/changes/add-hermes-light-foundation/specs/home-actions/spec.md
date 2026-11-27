# Home Actions Spec Delta

## Purpose

Home is the app. Navigation lives in its top bar as small actions, not on a separate page that would
sit half-empty on a 472dp screen. (Owner decision after alpha2: the full-page Menu read as wasted
space and was removed.)

## ADDED Requirements

### Requirement: Navigation actions live on Home

Home's top bar SHALL provide two small actions — **History** (opens Sessions) and **Bots** (opens
Bots) — and Settings SHALL open from a long-press back on Home. The app MUST NOT render a separate
menu page, drawer, sheet, or tab bar for navigation.

#### Scenario: Reachability

- **WHEN** the user is on Home
- **THEN** History and Bots are reachable with one action each, Settings with a long back press, and no page exists whose only content is a list of destinations

#### Scenario: Back chain

- **WHEN** the user opens Sessions and presses Back
- **THEN** they return to Home directly (no intermediate menu page)

#### Scenario: Wheel-only access without crossing the oldest thread boundary

- **WHEN** the user turns down past the newest message on Home
- **THEN** focus moves through History, Bots and the status strip if present, then returns to the thread; click activates the focused action
- **AND** empty Home starts at History, absent content is skipped, and turning up at the thread's oldest boundary never transfers focus

### Requirement: No half-empty screens

A full screen whose content occupies under half of the viewport SHALL be folded into an existing
surface instead of shipped as its own page.

#### Scenario: Adding a destination later

- **WHEN** a future feature needs a new destination
- **THEN** it is added as an action on an existing screen or as a session-list footer row, not as a new mostly-empty page
