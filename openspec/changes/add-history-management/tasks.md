# Tasks — add-history-management

One commit per group; gates green at every commit (ktlint, colour guard, unit tests, assembleDebug).

## 1. Search

- [x] 1.1 Sessions: a search action in the top bar opens the SDK text input; the query filters the loaded list by title and preview (case-insensitive) and the header reads `N of M` while active.
- [x] 1.2 Clearing the query restores the full list; a query with no matches shows one line `no sessions match`, and Back clears the query instead of leaving the screen.
- [x] 1.3 Unit tests for the filter (title, preview, case, empty query, no matches).

## 2. Rename

- [x] 2.1 Long-press a session row opens a two-row sheet: Rename / Delete, with the session title above it.
- [x] 2.2 Rename opens the SDK editor with the current title; empty or whitespace-only input is rejected; the saved title goes to `PATCH api/sessions/{id}` `{title}` (ids URL-encoded — they may contain `/`).
- [x] 2.3 On success the row updates in place; on failure the sheet closes with one line `rename failed` and the old title stays.

## 3. Delete

- [x] 3.1 Delete opens a confirm row naming the title ("Delete “<title>”? This cannot be undone."); only that row deletes, via `DELETE api/sessions/{id}`.
- [x] 3.2 On success the row leaves the list; if it was the open thread, the app returns to Sessions. On failure the row stays and one line reads `delete failed`.
- [x] 3.3 No optimistic removal: the row disappears only after the server confirms.
- [x] 3.4 Unit tests: confirm gating, removal-after-success, failure keeps the row.

## 4. Release

- [x] 4.1 Version 0.2.4 / code 108; evidence note appended.
- [ ] 4.2 Parent signs and publishes alpha8 (outside this implementation task).

## 5. Device findings — mutation fence + server search (from alpha8 testing)

- [x] 5.1 Rename failed with `HTTP 409` on the device: current backends refuse an unfenced PATCH. Read `GET api/sessions/{id}/mutation-snapshot` first (`runtime_revision`, `runtime_generation`, values may be int or string), then PATCH `{title, request_id, expected_revision, expected_generation}`. Same fence for DELETE via query params `request_id`, `expected_revision`, `expected_generation`. On `409 client_upgrade_required`, re-read the snapshot once and retry; if it still refuses, say `changed elsewhere — retry`.
- [x] 5.2 Search is client-side over the loaded page (`0 of 50` on 801 sessions). Use `GET api/sessions/search?q=&limit=` when the query is 2+ characters (server-wide), falling back to the local filter if the endpoint is unavailable; the header shows the true count from the response.
- [x] 5.3 Unit tests: fence acquisition and body shape for rename/delete, one 409 retry, and search endpoint fallback.
