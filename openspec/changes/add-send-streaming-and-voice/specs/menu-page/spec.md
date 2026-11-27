# Menu Page v2 Spec Delta

## Purpose

The menu page comes back — but carrying content, not just destinations. Owner decision after alpha3:
"History" and "Bots" as bare top-bar actions were reachability without substance; a menu that shows
state earns its page.

## ADDED Requirements

### Requirement: Menu is a page with state, reached in one action

Home's top bar SHALL carry a single **Menu** action. The Menu page SHALL open with a status block
(connection state, current model, session count) and then rows for **History**, **Bots**, **Cron**,
**Settings**, and **About** (name + version), in the Light row style. It MUST occupy the page with
content — no row list floating in empty space.

#### Scenario: Open the menu

- **WHEN** the user activates Menu on Home
- **THEN** the page shows the status block plus the five destination rows, and Back returns to Home

#### Scenario: Nothing is a dead end

- **WHEN** a destination has no live data yet (Cron with no jobs)
- **THEN** its row states the count (`Cron · 0 jobs`) instead of being disabled or opening an empty screen

### Requirement: Recent sessions on the menu

The Menu SHALL list the three most recent sessions below the destinations, each row opening that
thread directly, so the page continues to be useful after the destinations are learned.

#### Scenario: Jump to a recent thread

- **WHEN** the user activates a recent-session row
- **THEN** the thread opens for that session and repeated Back walks Sessions → Menu → Home

### Requirement: One action per screen, still

The page MUST NOT introduce tabs, a drawer, or a second menu. Back chain: Thread → Sessions → Menu →
Home, with Home reachable from any screen by repeated Back.

#### Scenario: Back chain

- **WHEN** the user presses Back from the Menu
- **THEN** Home appears, and no intermediate page exists
