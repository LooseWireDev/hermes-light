# AGENTS.md — Hermes Light

Instructions for any agent (Hermes, OMP, Codex, Claude) working in this repository.

## What this is

A Light SDK–native Hermes client for the Light Phone III. Small on purpose: six screens, one
thread, one deck. When a feature would add a seventh screen, it belongs on the desktop client
instead.

## Non-negotiables

1. **The phone is the constraint.** Design for 411×472dp, wheel + thumb, and a 3.92" OLED. New UI
   must be measured on the LP3-shaped emulator (see §Verification) before it counts as done.
2. **The SDK is the UI.** Use `com.thelightphone.sdk` widgets (`LightText`, `LightTopBar`,
   `LightBarButton`, `SimpleLightScreen`, `LightThemeTokens`). Material3 is allowed only where the
   SDK has no equivalent, and never for chrome (bars, sheets, chips, dialogs of the app's own).
3. **Wheel first.** Every list and thread must be driven by `WheelScroll`; every actionable row is
   focusable and openable with the wheel click; back returns one level. Keyboard/touch still work.
4. **Voice is a primary input.** Hold-wheel push-to-talk (`WheelTalk`) must stay working on every
   screen that accepts text. Never regress it for a layout change.
5. **Honest agent UI.** Streaming, tool activity, approvals and errors are shown as they are:
   nothing is claimed complete before the gateway says `done`; approvals are never auto-accepted;
   a dropped socket says so.
6. **Licenses.** BrightHermes is the owner's own code. Hermes Mobile is Apache-2.0: copied or
   derived code keeps its headers and gets an entry in `NOTICE`. Never copy anything else in.

## Build and gates

```bash
export ANDROID_HOME=$HOME/.local/share/light-tern-toolchain/android-sdk
export JAVA_HOME=$HOME/.local/opt/temurin21        # release + lint need 21; debug builds work on 17
./gradlew :app:assembleDebug                      # or the module name once scaffolded
./gradlew ktlintCheck checkColorLiterals           # color guard: no hardcoded Color literals
./gradlew testDebugUnitTest
```

- JDK 21 for `lintVital`/release; portable adb at `~/.local/opt/platform-tools`.
- Releases are built and signed **locally**, never in GitHub Actions (Actions stay disabled on the
  owner's forks; signing keys never leave the machine).

## Verification

- LP3 AVD: `mealforge_lpi3_ui_test_v1` on `emulator-5584` (1080×1240 @ 420dpi = 411×472dp).
  Disable its HOME app while driving: `adb shell pm disable-user --user 0 com.mealforge.lightphone`
  (re-enable afterwards).
- Screenshots: `adb exec-out screencap -p` → `~/Projects/light/hermes-light-audit/` (private; never
  commit).
- Live gateway for manual testing: the owner's dashboard over Tailscale, or a loopback dashboard on
  the host reached via `adb reverse tcp:9121 tcp:9121` (token auto-extraction, no credentials).

## Workflow

- OpenSpec first: every feature starts as a change under `openspec/changes/`, is implemented, and
  only then synced into `openspec/specs/`. `openspec/roadmap.md` owns sequencing.
- One commit per task group. ktlint + color guard green at every commit.
- Keep diffs small and additive; the app should stay under ~10k LOC for M1–M3.
