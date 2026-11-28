# Proposal — History management (search, rename, delete)

## Why

The phone now has 801 sessions and no way to find one. BrightHermes never had history at all; Hermes
Mobile had filters and pill tabs that ate the screen. The middle path: one search action, one
long-press sheet, two destructive-safe rows.

## What Changes

- Sessions gains SDK search input: server-wide matches for 2+ characters, a local title/preview
  fallback, and an honest displayed/matching `N of M` header.
- Long-press a row → Rename / Delete sheet; Delete needs a second confirm naming the title.
- Server truth: rename and delete are only reflected after the server agrees; failures are named.
- Rename/delete acquire runtime mutation fences and retry `409 client_upgrade_required` once;
  repeated refusal reports `changed elsewhere — retry` without an optimistic row change.

## Capabilities

### New Capabilities

- `history`: search, rename and delete over the session list.

### Modified Capabilities

- `session-triage`: adds long-press semantics to the existing list rows (tap still opens).

## Impact

- `ui/SessionsScreen.kt`, `ui/SessionActions.kt` (new sheet), `AppController` (search state, rename,
  delete), `net/Api.kt` (rename/delete calls), strings.
- No new permissions, no background work, no schema changes.
