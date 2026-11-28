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
append to the selected session's composer without sending. M1.5 extends this to the SDK editor:
release returns to editing, SAVE returns to the composer with Send focused, and sending is always a
separate explicit action. No blank or failed take invokes submission or closes the editor.

## Text-only overlap anchors

The dashboard returns plain text without token timestamps. The original timestamped
`DictationTake.Stitcher` stays available for a future local engine; server text uses a
conservative word-anchor completion path in the same take, not fabricated timestamps.
Ambiguous or missing anchors fail instead of silently duplicating or deleting speech.
This cannot provide the acoustic certainty of timestamped word ownership; repeated or
rewritten overlap text can reject an otherwise audible take. M1.5 displays only the windows
already stitched by DictationTake as a live preview; previews never commit a draft or send.

## Future on-device option

Sherpa-onnx with NVIDIA Parakeet TDT 110M int8 remains an option for offline operation
and latency. BrightHermes uses an **arm64-only** sherpa-onnx AAR and approximately
**120 MB** of model assets. It cannot run on the **x86_64** verification emulator and
would add model distribution/storage plus SDK dependency-policy work. M1.4 does not
package it or weaken SDK policy to admit it.

## M1.5 panel and composer

Listening and transcribing replace the entire Home/Thread content below the top bar; the editor
uses the same panel without disposing its text state or wheel binding. Thirteen high-contrast bars
sample the smoothed chunk RMS at 90 ms intervals, oldest left. A turn while held cancels immediately,
clears the take's telemetry and returns to the untouched draft. Release shows `transcribing…` until
the final result returns to the draft. The requested hint says “let go to send”; release actually
finishes dictation for review, not a gateway submission.

The SDK `LightTextField` opens its own multi-line `LightTextInputEditor`. Draft changes remain in
the controller's per-session conversations across screen navigation; SAVE returns with Send
focused. The adjacent send/queue action uses the existing submission path, never the voice callback.
Unknown model status occupies no placeholder row.

## M1.6 stale activity and idle queue recovery

The gateway `session.active_list` snapshot is shared by attention refresh, thread open/resume,
send preflight and queued-start checks. Runtime resume precedes open/send reconciliation so
aliases map back to stored sessions. Newer activity events outrank snapshots; malformed snapshots
cannot prove idleness. An idle snapshot clears the remembered running state and stale reply caret.

Five-second checks retain a legitimately busy queue. An idle queue is cancelled by admission id
before one direct resend of the same local row. The promotion marker survives foreground changes;
restarting its monitor never sends a second direct copy. Start events and fresh transcript evidence
win over polling/cancellation races. Refused or proven idle-after-promotion sends expose
`could not start — retry`. Unknown transport/cancellation outcomes remain non-retryable until
gateway evidence resolves them; losing certainty is not permission to execute twice.

No SDK widget, microphone path, package identity or version changes in M1.6.

## Acceptance boundary

Unit tests and the requested Gradle gates verify wire encoding, authenticated HTTP,
stitching and take lifecycle. This task explicitly forbids adb/emulator use. Actual
microphone capture, live dashboard audio and LP3-sized layout/hardware acceptance remain
unmeasured; a successful build is not device acceptance or official builder compatibility.
