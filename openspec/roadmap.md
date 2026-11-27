# Roadmap

Owns delivery sequencing for Hermes Light. Milestones are judged on the LP3-shaped emulator and,
at each milestone's end, on the real Light Phone III.

## M0 — Skeleton that boots and talks (first change: add-hermes-light-foundation)

New repository scaffold on the Light SDK: activity, theme, setup screen (endpoint + sign-in/token),
connectivity state machine, session list and read-only thread against the live dashboard. Voice and
deck arrive later; M0 proves the data plane and the wheel.

## M1 — Thread that behaves

Send, streaming render, tool one-liners, reasoning collapse, approvals and clarify as blocking
Light-native rows, queue/stop, reconnect honesty. Voice (hold-wheel dictation) carried over from
BrightHermes.

## M2 — Deck that earns its keep

Tiles from live data: Running sessions, Cron (next run + last outcome), Approvals pending, Unread.
Deck provider surface + in-app deck. Bots picker as a deck tile handled by the thread.

## M3 — Daily-driver polish

Long-thread paging, search over sessions, notification of replies while backgrounded (if LightOS
policy allows), setup hardening (password + token modes), install/release path (own keystore,
sideload via the owner's tools).

## Standing constraints

- Six screens maximum: Home (deck+thread), Sessions, Thread, Bots, Approvals-inline, Setup.
- Admin surfaces (kanban board, files, MCP, skills, logs, config editors) are out of scope forever;
  they live on the desktop client and in the Hermes Mobile fork.
- Never auto-approve; never fabricate `done`.
