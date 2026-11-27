# Hermes Light

**Your Hermes agent, sized for the Light Phone III.**

A Light SDK–native Android app (Kotlin, Compose, LightOS widgets) that puts the useful half of the
Hermes dashboard on the phone — live sessions, history, bots, cron and approvals — in a form a
thumb-and-wheel device can actually carry.

Hermes Light is not a port. It is a small, purpose-built client:

- **Info Deck on top** — glanceable tiles (running sessions, cron, approvals, unread).
- **Thread below** — read and send in any session, streamed live; **hold the wheel** to talk.
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

M0 implementation: dashboard setup, encrypted connection profile, recent sessions and source/profile
selection, read-only paged transcripts, and a placeholder deck. Socket status and desktop-only
approval/clarification notices are visible. Sending, streaming rendering, voice and live deck data
are **not implemented in M0**.

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
must verify the five screens and wheel/back behavior on the LP3-shaped emulator, then first connect
on the real phone. Acceptance and remaining checks live in
`openspec/changes/add-hermes-light-foundation/{tasks,evidence}.md`.

## Connection and controls

Setup accepts an HTTP(S) dashboard base URL, including reverse-proxy path prefixes. Token mode
extracts the loopback SPA token or accepts a pasted token. Password mode discovers providers,
establishes the dashboard cookie and obtains short-lived WebSocket tickets. The entire connection
attempt is bounded by 25 seconds; credentials are saved only after the socket authenticates.
HTTP is for trusted development networks: credentials and transcripts are otherwise exposed.
Profiles and per-session read positions are AES-GCM encrypted with an Android Keystore key; app
data backup must not be used to transfer that key to another device.

Turn the wheel to focus/scroll, click to open or expand, back to return one level. Back at Home
stays there; long-back opens Setup. Home's thread top leads into the deck, then Sessions. Tool and
reasoning rows expand; timestamps are available on demand. Approval and clarification notices are
read-only: respond on the desktop. No turn or approval-response RPC exists in this M0 client.

Chrome uses SDK widgets only. The annotated transcript-span renderer uses foundation `BasicText`
because the active SDK's `LightText` accepts only `String`; its typography and colors still come
from `LightThemeTokens`. The in-repo `WheelScroll` driver implements the BrightHermes wheel contract
using SDK device keys; it does not add the unallowlisted `com.gios:light-common` dependency.
