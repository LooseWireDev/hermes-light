# Home Actions Spec Delta

## Purpose

Home's top bar stays minimal, but a menu page that carries real state returns (see menu-page spec).
Supersedes alpha3's History + Bots actions.

## MODIFIED Requirements

### Requirement: Navigation actions live on Home

Home's top bar SHALL carry a single **Menu** action (plus the thread title). History, Bots, Cron,
Settings and About are reached from that page. The app MUST NOT render a drawer, tab bar, or a
gesture-based navigation surface.

#### Scenario: Reachability

- **WHEN** the user is on Home
- **THEN** one action opens the Menu, and every destination is one more action away

#### Scenario: Back chain

- **WHEN** the user presses Back on any non-Home screen
- **THEN** they land on the previous screen in the chain Thread → Sessions → Menu → Home

### Requirement: No half-empty screens

A full screen whose content occupies under half of the viewport SHALL be folded into an existing
surface instead of shipped as its own page — except the Menu, which earns its page by showing live
state plus recent sessions.

#### Scenario: Adding a destination later

- **WHEN** a future feature needs a new destination
- **THEN** it becomes a Menu row with a live count, not a new mostly-empty page
