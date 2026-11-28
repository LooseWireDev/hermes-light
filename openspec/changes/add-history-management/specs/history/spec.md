# History Management Spec Delta

## Purpose

801 sessions on a 472dp screen is a scroll marathon. History needs to be searchable, and a session
needs a name you recognise and a way to get rid of it — without a drawer, tabs, or a second menu.

## ADDED Requirements

### Requirement: Search the history

The Sessions screen SHALL offer a search action that opens the SDK text input. Queries of two or
more characters SHALL search `GET api/sessions/search` with `q`, a limit of 50, offset and the active
profile/source scope. The screen SHALL render the returned rows without filtering them again and
show `N of M`: rows actually shown versus the server's matching total when supplied. Short queries,
pending requests and endpoint failures SHALL use the local case-insensitive title/preview filter;
its denominator is the loaded list size. Stale responses MUST NOT replace a newer query or scope.
Clearing the query SHALL restore the loaded list, keeping the scroll position of the filtered result
the user was on.

#### Scenario: Find an old thread

- **WHEN** the user types "mortgage" in the search field
- **THEN** server-wide matching sessions are listed, the count reads returned rows of matching total, and tapping one opens it

#### Scenario: Nothing matches

- **WHEN** the query matches no session
- **THEN** the list shows one calm line `no sessions match` and Back clears the query rather than leaving the screen

#### Scenario: Search endpoint unavailable

- **WHEN** a search request fails
- **THEN** the local title/preview filter remains usable and `N of M` counts matches versus loaded rows

### Requirement: Rename and delete, with a confirm

A long-press on a session row SHALL open a two-row action sheet: **Rename** (SDK editor, empty input
rejected, trimmed) and **Delete** (a second confirm row naming the title; deletion is
`DELETE api/sessions/{id}`). Deleting the session currently open MUST return to Sessions. Rename and
delete MUST update the list in place without a full reload.

#### Scenario: Rename

- **WHEN** the user long-presses a session, picks Rename, edits and saves
- **THEN** the row keeps its old title until server confirmation, then shows the trimmed new title in place

#### Scenario: Delete needs two taps

- **WHEN** the user picks Delete
- **THEN** a confirm row appears naming the title, and only activating it deletes; the sheet can be dismissed with no change

### Requirement: Fence history mutations

Before each rename or delete attempt, the client SHALL read
`GET api/sessions/{id}/mutation-snapshot` and preserve `runtime_revision` and `runtime_generation`
as JSON values, including numeric and string counters. Rename SHALL PATCH `title`, a fresh
`request_id`, `expected_revision` and `expected_generation`; delete SHALL send the same fence fields
as query parameters. No mutation SHALL be sent without a valid fence for an existing session.

#### Scenario: Fence refused once

- **WHEN** a mutation returns `409 client_upgrade_required`
- **THEN** the client reads a fresh snapshot and retries once with a fresh request id

#### Scenario: Fence refused twice

- **WHEN** the retried mutation also returns `409 client_upgrade_required`
- **THEN** the sheet closes, the unchanged row remains, and the action error includes `changed elsewhere — retry`

### Requirement: Errors are named

A failed rename or delete MUST say which action failed and keep the row usable (no optimistic removal
without server confirmation).

#### Scenario: Delete rejected

- **WHEN** the server refuses the delete
- **THEN** the row stays, the sheet closes, and one line reads `delete failed` with the reason
