## 2026-11-27 — M1 implementation scope and protocol evidence

Extending the M0.3 socket/UI, not replacing the data plane. Local/development lane: the owner's
composite SDK `main` at `251237ef3778c907c5dbd89e84685532c9d99a28`; existing SDK edits left untouched.
No adb/emulator driving, screenshots, install, upload or push. Parent owns LP3 visual/wheel acceptance.
No specs have been synced as accepted device behavior.

Contract details that affect safety:

- `prompt.cancel` requires both `session_id` and the submit receipt's `admission_id`. A JSON-RPC
  success alone does not prove cancellation; inspect admission status/outcome.
- `approval.respond` uses choices `once`/`deny`/`always`; `Always` is not blanket `all:true`.
  It must be explicitly permitted by the request. RPC acknowledgement does not fabricate settlement.
- Canonical clarify replies echo `prompt_id` and `execution_generation` with `answer`; legacy
  replies use `response`/`answer` and the request/question identifiers.
- `approval.pending` queries pending approval details. Clarify has no pending-query RPC; the gateway
  replays `clarify.request` on reconnect.

Voice source mismatch discovered before porting: the current owner sources use sherpa-onnx's
`OfflineRecognizer` with Parakeet assets and `AudioRecord`, not Android `SpeechRecognizer`.
`DictationTake` explicitly limits capture to 300 seconds. The requested verbatim port cannot
honestly be described as unbounded or as a SpeechRecognizer loop. No alternate dictation pipeline
will be substituted without an owner decision.

### Safety correction to the proposed approval lifecycle

The reference gateway's `app/src/test/resources/gateway-contract/openrpc.json` defines
`approval.received` as a display acknowledgement, not a decision. It must not hide an unanswered
request. Rows/attention therefore remain pending on that event. Confirmed `approval.respond`
resolution or absence from `approval.pending` settles a request; `approval.cancelled` expires the
named requests. Tests that previously equated display acknowledgement with settlement are updated.
Canonical approval requests carrying `prompt_id` and `execution_generation` echo those fences;
legacy requests retain the supplied `request_id`/`choice`/`all:false` contract.

### Voice owner decisions required

1. Allow a supported sherpa-onnx artifact and model distribution through the SDK, and provide an
   approved capture/assets interface, or explicitly authorize a different recognizer/capture port.
   The active plugin blocks `android.content.Context` (line 140), rejects local file dependencies
   (line 519), and does not allowlist sherpa-onnx. No SDK policy was weakened and no unbuildable
   `Listener` stub was added.
2. Preserve the source's 300-second cap, or authorize changing its behavior to meet the unbounded
   requirement. The port preserves the cap; it does not claim ten-minute capture.
3. The SDK exposes permission check/request but no permission-settings action; raw
   `android.content.Intent` and lifecycle-owner access are blocked. The UI can request microphone
   permission through LightOS and rechecks it when the SDK screen is shown. Missing permission
   names LightOS Settings manually. A real Settings action needs an SDK API/owner-approved route.

`DictationTake` and the SDK-key-adapted `WheelTalk` are owner-attributed ports. `WheelTalk`'s private
BrightHermes wheel-bus interface is replaced by an `onTurn(steps, whileHeld)` callback using SDK
device keys; its hold/release/cancel state machine remains unchanged. It is not connected to a
nonexistent recognizer. No hold, capture, inversion or voice-send success is claimed in this build.

The canonical Obsidian vault was unavailable at `/home/gav/Documents/obsidian-vault`; no vault note
was changed. This evidence file contains the durable state and blockers.

### Implemented surfaces awaiting parent device acceptance

- Shared per-session composer and submission state; typed SEND/QUEUE goes through `prompt.submit`.
  Pending/queued/rejected/unknown outcomes stay distinct. Retry is explicit; an unknown transport
  outcome never triggers a blind resend. Cancellation uses the admission id and requires an
  explicit cancelled receipt. Stop does not fabricate `done`.
- Streaming/reasoning/tool/process reduction, assistant caret, collapsed one-line tool/reasoning
  rows, and REST reconciliation after completion/reconnect with socket-generation protection.
- Explicit blocking approval/clarify rows with hoisted state, first-choice wheel focus, gated
  Always, multi-question/multi-select answers, canonical generation fences and terminal states.
- Active-session/pending-approval snapshots feed the zero-height-idle strip. A remembered-session
  404 clears selection/read anchors silently and requests Sessions.
- Metadata is `103 / 0.2.0`. This is not alpha4 publication or full M1 voice acceptance.

All code is integrated before running gates; no builds/tests/lint run between task-group edits.
The parent retains visual acceptance boxes because this run is explicitly forbidden to drive the
AVD. No release signing or publishing is performed here.

### Final verification after the last code edit

With `ANDROID_HOME=$HOME/.local/share/light-tern-toolchain/android-sdk`,
`JAVA_HOME=$HOME/.local/opt/temurin21`, and Java 21 on PATH:

```sh
./gradlew -Porg.gradle.java.installations.paths="$HOME/.local/share/light-tern-toolchain/jdk17,$HOME/.local/opt/temurin21" ktlintCheck checkColorLiterals :tool:testDebugUnitTest :tool:assembleDebug :tool:assembleRelease --continue --console=plain
```

**BUILD SUCCESSFUL in 8s**. **140 tests across 19 files; zero failures, errors or skips**.
Coverage includes correlated/out-of-order RPC responses, rejection/disconnect/background/timeout/
replacement generations, submission/queue/stop, REST row baselines and identical repeated sends,
approval acknowledgement versus settlement/expiry, canonical decision fences, clarify locked
answers/generation replay, missing remembered-session state, and seven DictationTake tests including
the 180-second synthetic take (360 words, exactly one final send).

Initial gate runs exposed ktlint layout, internal coroutine continuation APIs, erased generic
helper JVM signatures, invalid test fixtures/expectations, and a real locked-answer default bug.
These were corrected; the final gate run above passed after the last code edit. Continuations now
use stable `resumeWith` with single-completion ownership enforced by the pending-call map.
The SDK's existing publication warning and AGP's compile-SDK-37 warning remain.

Artifacts:
- `tool/build/outputs/apk/debug/tool-debug.apk`
- `tool/build/outputs/apk/release/tool-release-unsigned.apk`

The release is unsigned; the parent signs/publishes only after acceptance. No adb/emulator,
screenshots, live dashboard, phone install, release signing, upload or push was performed.
Visual boxes, full voice tasks and parent publication remain unchecked. This is a verified local
build of the reachable work, not a claim that M1 or alpha4 is fully accepted.
