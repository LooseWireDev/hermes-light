# Menu Page Spec Delta

## Purpose

One page that says where the phone client can go — modelled on the Hermes Mobile drawer, re-done as
a full Light page: no drawer, no overlay, no chrome. Home owns the conversation; the Menu owns
navigation.

## ADDED Requirements

### Requirement: A menu page, not a drawer

The app SHALL provide a Menu destination rendered as a full page (title bar with Back, one
wheel-focusable row per destination, Light row styling) and SHALL NOT render navigation as an overlay
drawer, sheet, or persistent tab bar. Home SHALL expose a Menu action in its top bar; wheel-up past
the deck top MAY also open it.

#### Scenario: Opening the menu

- **WHEN** the user activates the Menu action on Home (or turns the wheel up past the deck)
- **THEN** the Menu page opens with the focus on the first row and no other overlay appears

#### Scenario: Back chain

- **WHEN** the user is on Thread and presses Back twice, then Back once more from Sessions
- **THEN** they land on Menu, then Home, in that order, with no skipped or repeated screens

### Requirement: Destinations and honest placeholders

The Menu SHALL list, in order: Chat (return to the current thread), History (Sessions), Bots, Cron,
Settings. A destination that is not implemented yet (Cron until M2) SHALL appear disabled with a
one-line note, never as a tappable row that does nothing.

#### Scenario: Unimplemented destination

- **WHEN** the user focuses the Cron row before M2
- **THEN** it reads "Cron — arrives with live tiles" and cannot be activated; focus moves past it normally

#### Scenario: Chat from menu

- **WHEN** the user selects Chat while a session is open
- **THEN** Home returns with that session's thread and its previous scroll position
