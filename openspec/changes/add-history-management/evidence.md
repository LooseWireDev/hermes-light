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

## 2026-11-27 — task group 5, alpha8 device findings

- Owner-confirmed alpha8 evidence (0.2.4 / 108): search, the `Session / Rename / Delete` sheet and
  honest failure feedback worked. Rename returned HTTP 409; the loaded-page search showed
  `0 of 50` / `1 of 50` against 801 sessions.
- Rename/delete now acquire `mutation-snapshot` before sending a mutation. Runtime revision and
  generation remain JSON values (integer or string); PATCH carries the title and fence in its body,
  DELETE carries the fence in query parameters. Each attempt uses a fresh request id.
- `409 client_upgrade_required` reacquires the snapshot and retries once. Repeated refusal reports
  `changed elsewhere — retry`; old titles and rows remain until the dashboard confirms success.
- Queries of 2+ characters use the scoped server search endpoint (50-result limit, 250ms debounce).
  Returned results are rendered without a second local filter; the header uses displayed count
  versus server matching total. Missing totals use the returned count. Endpoint errors fall back
  to loaded title/preview filtering with the loaded-row denominator.
  Search decoding accepts the Mobile `results` / `session_id` / `snippet` contract as well as
  session-list-shaped responses; repeated message hits render as one session row.
- Search responses are invalidated on query/scope changes and acknowledged mutations. Server-only
  result rows support the same wheel/touch actions and retain their title when opened as a thread.
- Package/version remain **dev.loosewire.hermeslight**, **0.2.4 / code 108**. Local/development lane;
  owner SDK `main` at `251237ef3778c907c5dbd89e84685532c9d99a28`, existing dirty SDK untouched.
- No adb/emulator, device install, publication or push. New on-device layout, wheel/voice and live
  dashboard acceptance remain unverified; accepted specs are not synced by this local task.
- Canonical Obsidian vault still unavailable. Intended durable update:
  `local://hermes-light-history-fences-vault.md`; no canonical vault update is claimed.

### Group 5 verification

After the last code edit, with the supplied Android SDK/JDK/toolchain paths:

- `./gradlew ... ktlintCheck :tool:testDebugUnitTest --continue --console=plain`:
  **BUILD SUCCESSFUL in 11s**.
- `./gradlew ... checkColorLiterals :tool:assembleDebug --console=plain`:
  **BUILD SUCCESSFUL in 1s**; debug APK rebuilt locally.
- The first combined gate run found ktlint wrapping and two JUnit methods inferred as returning
  UUID rather than Unit. Formatting and explicit `runBlocking<Unit>` corrected those failures.
- Regression coverage includes mixed-type fences, oversized integer strings, snapshot failures,
  one conflict then success, two conflicts with no third attempt, honest unchanged-row error
  feedback, both search envelopes/counts, local fallback, stale-query/invalidation handling and
  confirmed-only actions on server-only results.
- Existing Android publication/compile-SDK compatibility warnings remain; no SDK edits were made.
- No runtime visual proof under the explicit no-adb/no-emulator restriction. Owner device
  acceptance of these new network paths remains the next verification step.
