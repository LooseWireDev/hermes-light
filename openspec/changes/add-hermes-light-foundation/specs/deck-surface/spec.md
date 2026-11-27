# Status Strip Spec Delta

## Purpose

A single glanceable line above the thread that answers "does anything need me?" — and occupies zero
height when the answer is no. (Owner decision after alpha2: a permanent deck header read as dead
space on the phone.)

## ADDED Requirements

### Requirement: Conditional strip, zero space when idle

Home SHALL render a status strip above the thread ONLY when something needs attention (a running
turn, a pending approval, a fired cron job, unread replies). When nothing needs attention the strip
MUST render nothing and occupy zero height — no header, no placeholder, no reserved space. The strip
SHALL be one line: a short summary plus a count, and activating it opens the relevant surface.

#### Scenario: Nothing needs attention

- **WHEN** no turn is running and nothing is pending
- **THEN** the strip is absent and the thread starts at the top of the content area, with no label and no blank row

#### Scenario: Something needs attention

- **WHEN** one approval is pending and a turn is running
- **THEN** one line reads e.g. "1 approval · 1 running", activating it opens the approval

### Requirement: Focus crossing without a deck

Wheel-up from the thread's top SHALL remain a scroll gesture only; when it reaches the top nothing
else opens. Navigation is performed through Home's top-bar actions (home-actions spec).

#### Scenario: Wheeling past the top

- **WHEN** the thread is at its top and the user keeps turning up
- **THEN** nothing opens and nothing changes, and the status strip (if present) remains a passive line
