# Wheel and Voice Spec Delta

## Purpose

Holding the wheel must talk. Today nothing listens: `WheelTalk` exists but no screen claims a key, so
every notch and click is forwarded to LightOS. The wheel is also arbitrated phone-wide by
BrightControl (LightControl), which spends bare turns on brightness and the click on the torch unless
the app's package is hands-off there.

## ADDED Requirements

### Requirement: Screens claim the wheel

The thread (and any screen that scrolls) SHALL implement the SDK key handler and return `true` for the
wheel keys — `WHEEL_CCW`, `WHEEL_CW`, `WHEEL_CLICK` (and `FOCUS`/`CAMERA` when present) — on both DOWN
and UP, feeding them to the wheel state machine. Claiming both halves is what stops a notch becoming a
brightness change and a click becoming the torch. Keys the app does not claim MUST fall through
untouched.

#### Scenario: Notch scrolls and is not a brightness step

- **WHEN** the user turns the wheel in the thread
- **THEN** the key is claimed by the screen, the list scrolls one step, and the panel brightness does not change

#### Scenario: Nothing listens

- **WHEN** no screen claims the wheel keys
- **THEN** this is a defect, not a configuration: the requirement is that the app claims them

### Requirement: Hold to talk, click to act, turn to cancel

A press that lasts `HOLD_MS` with no turn SHALL start a voice take; release SHALL stop capture and
submit the stitched transcript through the same `prompt.submit` path as typed text. A press shorter
than `HOLD_MS` with no turn SHALL be a click whose action is: send the composed text (or stop the
running turn). A turn while held SHALL cancel the take and send nothing.

#### Scenario: Hold, speak, release

- **WHEN** the user holds the wheel for longer than `HOLD_MS`, speaks, and releases
- **THEN** the take is transcribed and submitted as one message, and the thread shows it as the user's turn

#### Scenario: Turn while held

- **WHEN** the user turns the wheel during a hold
- **THEN** the take is discarded, nothing is sent, and the turn does not scroll the thread

### Requirement: Diagnose a press that never arrives

The thread SHALL carry a discreet diagnostics readout (long-press the status line) reporting whether
wheel clicks and turns have been seen since launch, and whether the microphone permission is granted.
"Hold does nothing" has two causes — the key never arrived, or capture failed — and this tells them
apart.

#### Scenario: Click never seen

- **WHEN** the user opens diagnostics after holding the wheel with no effect
- **THEN** the readout shows clicks: 0, turns: N (or 0), and names the BrightControl per-app rule as the likely cause when both are zero while the wheel scrolls elsewhere

### Requirement: Microphone permission is asked for, once, honestly

The app SHALL request `RECORD_AUDIO` the first time voice is used, and on denial show a specific line
naming LightOS Settings. Voice MUST NOT start capturing before the grant.

#### Scenario: Denied

- **WHEN** the user denies the microphone permission
- **THEN** the thread shows one line explaining how to enable it, and holds do nothing further until granted
