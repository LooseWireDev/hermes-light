# M1.4 — dashboard-backed voice engine

## Decision

LightOS has no system speech recognizer. Capture with `AudioRecord` using
`VOICE_RECOGNITION`, 16 kHz mono PCM16; transcribe with the dashboard. No native
recognizer, model download, new dependency or ABI restriction is added to the APK.
This supersedes the original M1 assumption that Listener was a SpeechRecognizer loop.

Each required `DictationTake` window becomes a 44-byte RIFF/WAVE header plus PCM,
then an unwrapped `data:audio/wav;base64,...` URL. POST `api/audio/transcribe` with
`{data_url, mime_type: "audio/wav"}` using the existing bearer token or shared
password-session cookie. Empty `transcript` is successful silence; missing or invalid
transcript fields are failures. Audio is held in memory, not saved to disk or logged.
The dashboard receives the audio; dictation is not offline or phone-only processing.

## Capture and consent

The existing 28-second windows, 24-second stride, 56-second capture backlog and
300-second cap remain. One decoder runs at a time alongside capture. Falling behind,
failed capture, failed HTTP or an unreconcilable seam discards the entire take; no
partial transcript enters the composer or send path. No automatic upload retry is added.
Releasing commits once, stops capture, drains the final window and produces at most
one final result. Reaching the cap does not authorize submission before release.
Turn-to-cancel, screen hide, backgrounding, session/profile replacement and editor
close cancel the take and invalidate late microphone-permission/network callbacks.

States are `listening` while held, `transcribing…` after release, `no speech` for an
empty take, and exactly `transcription needs the dashboard` for a failed take. Existing
microphone-denial guidance continues to name LightOS Settings. Home and Thread release
append to the selected session's composer without sending. Inside the composer editor,
release is explicitly send-and-close; it uses the same submit/queue path as typed text.
No blank or failed take invokes submission or closes the editor.

## Text-only overlap anchors

The dashboard returns plain text without token timestamps. The original timestamped
`DictationTake.Stitcher` stays available for a future local engine; server text uses a
conservative word-anchor completion path in the same take, not fabricated timestamps.
Ambiguous or missing anchors fail instead of silently duplicating or deleting speech.
This cannot provide the acoustic certainty of timestamped word ownership; repeated or
rewritten overlap text can reject an otherwise audible take. There are no speculative
live transcript previews or partial-result commits.

## Future on-device option

Sherpa-onnx with NVIDIA Parakeet TDT 110M int8 remains an option for offline operation
and latency. BrightHermes uses an **arm64-only** sherpa-onnx AAR and approximately
**120 MB** of model assets. It cannot run on the **x86_64** verification emulator and
would add model distribution/storage plus SDK dependency-policy work. M1.4 does not
package it or weaken SDK policy to admit it.

## Acceptance boundary

Unit tests and the requested Gradle gates verify wire encoding, authenticated HTTP,
stitching and take lifecycle. This task explicitly forbids adb/emulator use. Actual
microphone capture, live dashboard audio and LP3-sized layout/hardware acceptance remain
unmeasured; a successful build is not device acceptance or official builder compatibility.
