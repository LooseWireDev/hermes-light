# History Management Spec Delta

## Purpose

801 sessions on a 472dp screen is a scroll marathon. History needs to be searchable, and a session
needs a name you recognise and a way to get rid of it — without a drawer, tabs, or a second menu.

## ADDED Requirements

### Requirement: Search the history

The Sessions screen SHALL offer a search action that opens the SDK text input; the query SHALL filter
the list as it is typed (case-insensitive, matching session title and the last message preview) and
the screen SHALL show `N of M` while a query is active. Clearing the query SHALL restore the full
list, keeping the scroll position of the filtered result the user was on.

#### Scenario: Find an old thread

- **WHEN** the user types "mortgage" in the search field
- **THEN** only sessions whose title or preview contains it are listed, the count reads `N of M`, and tapping one opens it

#### Scenario: Nothing matches

- **WHEN** the query matches no session
- **THEN** the list shows one calm line `no sessions match` and Back clears the query rather than leaving the screen

### Requirement: Rename and delete, with a confirm

A long-press on a session row SHALL open a two-row action sheet: **Rename** (SDK editor, empty input
rejected, trimmed) and **Delete** (a second confirm row naming the title; deletion is
`DELETE api/sessions/{id}`). Deleting the session currently open MUST return to Sessions. Rename and
delete MUST update the list in place without a full reload.

#### Scenario: Rename

- **WHEN** the user long-presses a session, picks Rename, edits and saves
- **THEN** the row shows the new title immediately and the server agrees on the next refresh

#### Scenario: Delete needs two taps

- **WHEN** the user picks Delete
- **THEN** a confirm row appears naming the title, and only activating it deletes; the sheet can be dismissed with no change

### Requirement: Errors are named

A failed rename or delete MUST say which action failed and keep the row usable (no optimistic removal
without server confirmation).

#### Scenario: Delete rejected

- **WHEN** the server refuses the delete
- **THEN** the row stays, the sheet closes, and one line reads `delete failed` with the reason
