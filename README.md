# Hermes Light

**Your Hermes agent, sized for the Light Phone III.**

A Light SDK–native Android app (Kotlin, Compose, LightOS widgets) that puts the useful half of the
Hermes dashboard on the phone — live sessions, history, bots, cron and approvals — in a form a
thumb-and-wheel device can actually carry.

Hermes Light is not a port. It is a small, purpose-built client:

- **Conditional status strip** — one line for running turns and pending notices; zero height when idle.
- **Thread below** — read and send in any session, streamed live. Voice runtime remains blocked (below).
- **Wheel-first** — scroll, page, open, back; no chrome, no chip rows, no sheets that eat the screen.

## Lineage

| | used for |
| --- | --- |
| [gi-os/BrightHermes](https://github.com/gi-os/BrightHermes) | the body: Light SDK shell, wheel widgets, `WheelTalk` hold-to-talk + dictation, deck-tile model, protocol-honest UI patterns. |
| [Hy4ri/hermes-mobile](https://github.com/Hy4ri/hermes-mobile) (Apache-2.0) | the brain: the Hermes dashboard REST/WS contract — sessions, messages, streaming events, approvals, clarify, cron, auth. |
| [lightphone/light-sdk](https://github.com/lightphone/light-sdk) | the design system and app framework (`SealedLightActivity`, `LightText`, `LightTopBar`, wheel helpers). |

Reuse and attribution rules live in `AGENTS.md`; the protocol surface is documented in
`docs/research/`.

## Status

M1 implementation adds the SDK composer, pending/queued sends, streaming replies, stop/cancel and
explicit inline approval/clarify decisions to M0.3's paged transcripts and encrypted connection
profile. The status strip follows live turn and approval state; remembered-session 404s return to
History without a raw error. No menu page, deck placeholder or Material chrome.

**Voice is not accepted:** the current BrightHermes port source is an offline sherpa-onnx/Parakeet
pipeline, not `SpeechRecognizer`, and caps takes at five minutes. Preserving that source while
providing the required runtime needs an owner decision; see the M1 evidence and unchecked tasks.
No emulator/phone verification or alpha4 publication is implied.

## Local build

```bash
export ANDROID_HOME=$HOME/.local/share/light-tern-toolchain/android-sdk
export JAVA_HOME=$HOME/.local/opt/temurin21
export PATH=$JAVA_HOME/bin:$PATH
./gradlew ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug
```

The `:tool` module uses the owner's SDK composite build at `../light-sdk`; override with
`-PlightSdk.path=/path/to/light-sdk`. Compile/target SDK 37, min SDK 34, JVM 21. The SDK plugin
generates the manifest and Android activity from `tool/lighttool.toml`; `MainActivity` is its
`@InitialScreen`. This is a local/development lane, not a verified official builder release.

The included SDK plugin compiles with a JDK 17 toolchain even though the tool targets JVM 21.
If Gradle cannot discover one, install `mise install java@temurin-17` and pass
`-Porg.gradle.java.installations.paths="$(mise where java@temurin-17)"` to the build command.

The debug key stays at `~/keys/hermes-light/hermes-light-dev.jks` (see `AGENTS.md`). Create it on
another workstation with:

```bash
mkdir -p ~/keys/hermes-light
keytool -genkeypair -keystore ~/keys/hermes-light/hermes-light-dev.jks \
  -storetype JKS -storepass android -keypass android -alias hermes-light-dev \
  -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Hermes Light Development"
```

Debug artifact: `tool/build/outputs/apk/debug/tool-debug.apk`. Tool Manager upload uses light-tern's
developer inbox HMAC protocol, with TLS verification enabled:

```bash
./gradlew :tool:uploadTool -Pdevice.ip=192.168.1.42 -Pdevice.token=<hex-hmac-key>
```

No upload, emulator install or hardware acceptance is implied by a local build. The parent/owner
must verify the pages and wheel/back behavior on the LP3-shaped emulator, then first connect
on the real phone. Acceptance and remaining checks live in
`openspec/changes/add-send-streaming-and-voice/{tasks,evidence}.md`.

## Connection and controls

Setup accepts an HTTP(S) dashboard base URL, including reverse-proxy path prefixes. Token mode
extracts the loopback SPA token or accepts a pasted token. Password mode discovers providers,
establishes the dashboard cookie and obtains short-lived WebSocket tickets. The entire connection
attempt is bounded by 25 seconds; credentials are saved only after the socket authenticates.
HTTP is for trusted development networks: credentials and transcripts are otherwise exposed.
Profiles and per-session read positions are AES-GCM encrypted with an Android Keystore key; app
data backup must not be used to transfer that key to another device.

Turn the wheel to focus/scroll, click to open or expand, back to return one level. Home's History
and Bots actions open their screens directly. Back follows Thread → Sessions → Home; long-back
opens Setup. Wheeling beyond the thread's oldest row never changes focus or navigates elsewhere.
On Home, turning down past the newest message focuses History, Bots and any active strip before
returning to the thread; `›` marks the focused action. Empty Home starts at History.
The strip prioritizes pending approvals, then clarifications, then running sessions; idle means no
strip and no reserved space. Setup starts with an editable loopback address, saved only after connection succeeds.
Tool rows show names (call ids only as a fallback) and outcomes; tool/reasoning rows expand and
timestamps are available on demand. Secondary text uses the next SDK tone (`content`).
In a thread, open Compose and use the SDK editor's SEND/QUEUE action. Sends remain pending until
gateway evidence, and rejected text returns to the draft with a retry row. Queued cancellation
uses its admission receipt. Stop waits for gateway completion; a lost RPC reply is outcome-unknown,
not permission to resend. Approval Yes/No/Always and clarify rows require explicit activation;
Always appears only when permitted. Nothing answers automatically or on navigation.

Chrome uses SDK widgets only. The annotated transcript-span renderer uses foundation `BasicText`
because the active SDK's `LightText` accepts only `String`; its typography and colors still come
from `LightThemeTokens`. The in-repo `WheelScroll` driver implements the BrightHermes wheel contract
using SDK device keys; it does not add the unallowlisted `com.gios:light-common` dependency.
