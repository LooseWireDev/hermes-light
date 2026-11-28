# History management evidence

## 2026-11-27 — task groups 1–3, local implementation

- Sessions offers SDK SEARCH, a live case-insensitive filter over loaded titles/previews and an
  `N of M` header while the query is active. CLEAR restores the list; Back clears the query before
  leaving. Clearing a viewed result restores its first visible session and pixel offset when present.
- Touch long-press or wheel hold opens an in-screen title + Rename / Delete sheet. Both action rows
  are wheel-focusable. Search is wheel-reachable above New session; no navigation destination added.
- Rename uses the SDK editor with the existing title; whitespace-only titles are rejected. Titles
  are trimmed, PATCHed and only changed in place after success. Errors close the sheet, name
  `rename failed` and leave the original row usable.
- Delete first opens the separate confirmation naming the title. Only its activation sends DELETE;
  dismissal does not. The row remains pending acknowledgement. Failure names `delete failed` and
  retains the row; deleting the selected session clears that thread and returns to Sessions.
- Mutation paths encode ids as one URL segment, including `/`, preserve proxy prefixes and reuse
  REST authentication. Neither operation optimistically changes the list or changes session ids.
- Search/rename editors retain WheelTalk hold/release/turn-to-cancel dictation into their own text,
  never into the selected thread or an automatic prompt submission.
- Package: **dev.loosewire.hermeslight**. Metadata: **versionCode 108 / versionName 0.2.4**.
- Local/development lane, owner SDK `main` at `251237ef3778c907c5dbd89e84685532c9d99a28`.
  Existing SDK modifications are untouched; no official-builder compatibility is inferred.
- No adb/emulator, install, signing for publication, upload, publication or push. Actual LP3 layout,
  wheel/touch, microphone and live-dashboard acceptance remain unverified under this task's rules.
  Accepted specs are not synced before that acceptance.
- The canonical Obsidian vault is unavailable at `/home/gav/Documents/obsidian-vault`.
  Intended durable project update: `local://hermes-light-history-vault.md`.

## Verification

Requested local gates (with the supplied Android SDK, JDK 21 and both toolchain paths):

```sh
./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug --continue --console=plain
```

Results are recorded below after execution.

**Final run: BUILD SUCCESSFUL in 10s.** `ktlintCheck`, `checkColorLiterals`,
`:tool:testDebugUnitTest` and `:tool:assembleDebug` all passed after the last code edit.
The initial run passed tests, the color guard and APK assembly but found a missing declaration
blank line and test assertion chain wrapping; those formatting issues were corrected before
the final run. No runtime or publication acceptance is inferred.
