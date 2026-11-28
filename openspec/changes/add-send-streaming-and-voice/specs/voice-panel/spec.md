# Voice Panel and Composer Spec Delta

## Purpose

Owner acceptance of alpha6: dictation works. Two gaps: no visible sign that dictation is happening
(wanted BrightHermes' full-area listening panel with a waveform, then a compose step), and the
composer should be a real Light SDK text field rather than a `· Compose` row.

## ADDED Requirements

### Requirement: Listening takes the screen, visibly

While a take is recording, the thread MUST show a listening panel that occupies the content area:
a moving waveform built from live microphone level (13 bars, ~90 ms per step, oldest at the left),
the partial transcript as it accumulates (windows stitched so far), and one hint line
"let go to send · turn the wheel to cancel". The thread's messages MUST NOT be visible behind it.

#### Scenario: Hold and speak

- **WHEN** the user holds the wheel
- **THEN** within ~100 ms the panel replaces the thread, the bars react to speech, and the partial text grows as windows decode

#### Scenario: Release

- **WHEN** the user releases after a take
- **THEN** the panel switches to a `transcribing…` state, then the final text appears in the composer and the thread returns

### Requirement: A real text field for the composer

The thread's composer SHALL be the SDK's own text input (`LightTextField` opening
`LightTextInputEditor`), with the label "Message" and a placeholder naming the wheel gesture. It MUST
NOT be a row that only says "Compose". A send action MUST sit beside it and show focus state; the
composer MUST hold multi-line text and preserve a draft across navigation.

#### Scenario: Type instead of talk

- **WHEN** the user taps the composer
- **THEN** the SDK editor opens with the existing draft, and saving returns the text to the composer with focus on the send action

#### Scenario: Draft survives

- **WHEN** the user leaves the thread and returns
- **THEN** the unfinished draft is still there

### Requirement: Quiet status when nothing is known

The menu/thread status block MUST NOT print `Model unavailable`; it shows the model when one is
resolved and omits the fragment otherwise.

#### Scenario: No model yet

- **WHEN** no model is available
- **THEN** the status line reads only the connection state (and counts), with no placeholder text
