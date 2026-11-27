# Approvals Spec Delta

## Purpose

Unblock the agent from the phone — safely. An approval is a decision, not a dismissible notice.

## ADDED Requirements

### Requirement: Blocking approval rows

`approval.request` SHALL render in the thread as a blocking set: the question, the requesting tool or
site, then `Yes` / `No` / `Always` rows (the last only when the payload permits `all`). Focus SHALL
start on `Yes`. The rows MUST remain until the request is answered, expires, or settles — and
nothing MAY be answered automatically, on screen-open, or by any default action.

#### Scenario: Approve

- **WHEN** the user selects `Yes` on a pending approval
- **THEN** the app sends `approval.respond {session_id, request_id, choice, all:false}`, shows "sending…" until `approval.received`, then replaces the rows with the outcome

#### Scenario: Never auto-accept

- **WHEN** the screen is not open, or the app starts with a pending approval waiting
- **THEN** no response is sent; the approval renders as pending and the Home strip shows one line pointing at it

#### Scenario: Expired

- **WHEN** the approval expires before an answer
- **THEN** the rows are replaced by "expired — answer on desktop" and the strip clears

### Requirement: Clarify options

`clarify.request` SHALL render its options as selectable rows; activation sends `clarify.respond`
with the chosen option. A settled or expired clarify MUST stop accepting input.

#### Scenario: Choose an option

- **WHEN** the user activates one clarify option
- **THEN** `clarify.respond` is sent for that option and the rows show the settled state on `clarify.settled`
