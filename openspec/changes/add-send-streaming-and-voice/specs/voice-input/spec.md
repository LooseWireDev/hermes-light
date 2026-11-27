# Voice Input Spec Delta

## Purpose

Hold the wheel, talk for as long as the thought takes, let go, and have it sent — outdoors, in
sunlight, without typing. Long-form dictation is a first-class input in Hermes Light, not a shortcut.

## ADDED Requirements

### Requirement: Hold-to-talk on the wheel

Holding the wheel (or the on-screen push-to-talk line) SHALL start capture; releasing SHALL finish
capture and hand the transcript to the send path; during capture, turning the wheel SHALL cancel and
discard the take. While listening, the panel MUST visibly invert (light background, dark text) so the
state reads in direct sunlight, and the captured text MUST be sent without requiring a second press.

#### Scenario: Long thought, one take

- **WHEN** the user holds the wheel for several minutes without pausing
- **THEN** one transcript is produced and sent on release, with no truncation and no duplicated words at decode-window seams

#### Scenario: Cancel mid-sentence

- **WHEN** the user turns the wheel while still holding
- **THEN** capture stops, the text is discarded, nothing is sent, and the panel returns to its normal state

### Requirement: Chunked, unbounded transcription

Transcription SHALL decode successive overlapping windows of the take and stitch them by matching
word anchors (the `DictationTake` approach from BrightHermes), so recording length is bounded only by
patience, not memory or a recognizer session limit. The live preview during capture MUST stay bounded
(a short tail), never rendering the whole take.

#### Scenario: Ten-minute take

- **WHEN** a take exceeds several decode windows
- **THEN** the stitched transcript is continuous (no repeated boundary words, no gaps), and memory use stays flat as the take grows

#### Scenario: Recognizer drops a window

- **WHEN** a window fails to decode
- **THEN** the take continues, the failed window is skipped in the preview, and the final transcript reports itself without inventing text

### Requirement: Voice never regresses

Every screen that accepts text SHALL support hold-to-talk, including the thread while a reply is
streaming. A layout change or new feature MUST NOT disable the wheel-hold gesture or the send-on-release
behaviour.

#### Scenario: Streaming reply, user holds wheel

- **WHEN** a reply is still streaming and the user holds the wheel and speaks
- **THEN** dictation starts, and on release the message is queued through the same path the text composer uses

### Requirement: Honest voice failures

Missing microphone permission, recognizer unavailability and decode failures SHALL be visible on
screen as specific lines with a retry affordance, never silently dropped audio.

#### Scenario: Permission denied

- **WHEN** microphone permission is refused
- **THEN** the push-to-talk line says so and offers to open settings; no empty message is sent
