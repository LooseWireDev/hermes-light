# Voice Input Spec Delta

## Purpose

Hold the wheel, speak, release once. M1.4 uses dashboard transcription because LightOS has no
system speech recognizer; it supersedes the earlier unbounded/on-device assumptions.

## ADDED Requirements

### Requirement: Hold-to-talk on the wheel

Holding the wheel SHALL start microphone capture after the SDK grants permission. Releasing
SHALL commit once and drain the final window. Home, Thread and the composer editor SHALL append
the result to the draft without sending. The editor SHALL save back to the visible composer with
Send focused; submission requires a separate action. Turning while held SHALL cancel and discard
the take.

#### Scenario: Long thought, one take

- **WHEN** the user holds the wheel for several minutes without pausing
- **THEN** one stitched transcript is produced only after release; no partial result is committed

#### Scenario: Cancel mid-sentence

- **WHEN** the user turns the wheel while still holding
- **THEN** capture stops, the text is discarded, nothing is sent, and the panel returns to its normal state

### Requirement: Bounded windowed dashboard transcription

Capture SHALL use AudioRecord VOICE_RECOGNITION, 16 kHz mono PCM16. Each required DictationTake
window SHALL be encoded as WAV with a 44-byte header and POSTed to `api/audio/transcribe` as
`{data_url, mime_type}` using the current dashboard authentication. Windowing SHALL retain the
28-second input, 24-second stride, 56-second backlog and 300-second cap. Reaching the cap SHALL NOT
authorize an early result. Text-only transcripts SHALL use conservative overlap anchors without
inventing timestamps; ambiguous or missing anchors SHALL discard the take, not guess a seam.

#### Scenario: Three overlapping windows

- **WHEN** three windows have reconcilable overlap words
- **THEN** their text is stitched without duplicate seams and exactly one final result is emitted

#### Scenario: Window fails

- **WHEN** capture, transcription or required overlap reconciliation fails
- **THEN** the whole take is discarded, no partial draft or message is committed, and the UI says `transcription needs the dashboard`

#### Scenario: Empty transcript

- **WHEN** the dashboard returns empty text for the take
- **THEN** the UI says `no speech`; nothing is sent and the editor stays open

### Requirement: Voice never regresses

Home, Thread and the composer editor SHALL support hold-to-talk, including while a reply streams.
The UI SHALL say `listening` while held and `transcribing…` after release until a final result.
Navigation, backgrounding, session/profile replacement and editor close SHALL invalidate late work.

#### Scenario: Streaming reply, user holds wheel

- **WHEN** a reply is still streaming and the user holds the wheel and speaks
- **THEN** dictation starts and release only edits the draft; a subsequent explicit send queues through the typed submission path

### Requirement: Honest voice failures

Missing microphone permission SHALL name LightOS Settings. A failed capture or dashboard request
SHALL display `transcription needs the dashboard` and never submit text from the discarded take.

#### Scenario: Permission denied

- **WHEN** microphone permission is refused
- **THEN** the UI names LightOS Settings and captures/sends nothing; a later grant requires a new hold
