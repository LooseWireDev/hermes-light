# Live Strip Spec Delta

## Purpose

The Home strip earns its single line by being true: it shows what is actually happening, and it
disappears the moment nothing does.

## ADDED Requirements

### Requirement: Strip reflects live run and approval state

The Home strip SHALL be fed by socket state: a running turn and a pending approval each contribute to
one summary line (`1 approval · 1 running`), with the approval taking precedence when activating it.
Stale state (a run that ended while the screen was closed) MUST NOT keep the strip visible after the
thread reconciles with the gateway.

#### Scenario: Approval pending

- **WHEN** an approval is waiting and no turn is running
- **THEN** the strip reads "1 approval" and activating it opens the approval in the thread

#### Scenario: Run ends

- **WHEN** the running turn completes and the reply is settled
- **THEN** the strip disappears within the next state update and the thread occupies the full height

### Requirement: Zero height when idle is not negotiable

When no condition holds the strip MUST compose nothing and reserve no vertical space; this holds on
every navigation return to Home.

#### Scenario: Return to Home

- **WHEN** the user returns to Home with nothing running or pending
- **THEN** there is no strip, no label, and no gap above the first message or the composer
