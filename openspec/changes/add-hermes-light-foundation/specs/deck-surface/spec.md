# Deck Surface Spec Delta

## Purpose

Home's top zone: glanceable, wheel-reachable state that answers "what needs me?" in one look. M0
establishes the surface (a placeholder), M2 fills it with live tiles.

## ADDED Requirements

### Requirement: Two-zone Home

Home SHALL present a deck zone above the thread zone. Wheel-up from the thread's top SHALL move focus
into the deck; wheel-up from the deck's top SHALL open the Sessions screen. The deck zone MUST not
scroll the thread or steal focus while the user is reading.

#### Scenario: Focus crossing

- **WHEN** the thread is scrolled to its top and the user turns the wheel up once more
- **THEN** focus enters the deck at its bottom tile, and turning back down returns focus to the thread where it left off

#### Scenario: Empty deck

- **WHEN** no tiles are available (M0)
- **THEN** the deck shows a single calm line stating that tiles arrive with live data, and the thread is fully usable above it
