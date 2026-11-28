# Roadmap

Owns delivery sequencing for Hermes Light. Milestones are judged on the LP3-shaped emulator and,
at each milestone's end, on the real Light Phone III.

## M0 — Skeleton that boots and talks (first change: add-hermes-light-foundation)

New repository scaffold on the Light SDK: activity, theme, setup screen (endpoint + sign-in/token),
connectivity state machine, session list and read-only thread against the live dashboard. Voice and
deck arrive later; M0 proves the data plane and the wheel.

## M1 — Thread that behaves

Send, streaming render, tool one-liners, reasoning collapse, approvals and clarify as blocking
Light-native rows, queue/stop, reconnect honesty. M1.4 uses dashboard-backed voice: `WheelTalk`
hold/release/turn-to-cancel, `DictationTake` overlapping windows with conservative word-anchor
stitching, and `Listener` AudioRecord capture. The existing 300-second cap remains. Home/Thread
release inserts a draft; M1.5 extends that review-before-send rule to the SDK editor. M1.5 adds a
full-area listening/transcribing panel with live levels and stitched text, plus an SDK Message field
and adjacent Send action. On-device recognition is future work (arm64-only sherpa-onnx and
approximately 120 MB Parakeet model).

## M2 — Deck that earns its keep

Strip content from live data: running turns, pending approvals, cron fired/unread. One line, zero height
when idle. No tiles, no permanent header.

## M3 — Daily-driver polish

Long-thread paging, search over sessions, notification of replies while backgrounded (if LightOS
policy allows), setup hardening (password + token modes), install/release path (own keystore,
sideload via the owner's tools).

## Standing constraints

- Six screens maximum: Home (conditional strip + thread), Menu (status + destinations + recents),
  Sessions, Thread, Bots, Setup; approvals stay inline. Menu v2 supersedes the alpha2 no-menu
  decision. Cron and About expand within Menu; no drawer or tabs. Back: Thread → Sessions → Menu →
  Home. A page whose content fills under half the viewport does not ship.
- Admin surfaces (kanban board, files, MCP, skills, logs, config editors) are out of scope forever;
  they live on the desktop client and in the Hermes Mobile fork.
- Never auto-approve; never fabricate `done`.
