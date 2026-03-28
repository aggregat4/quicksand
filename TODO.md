# TODO

Current verified baseline:

- Java 25 application on Helidon 4
- `mvn clean test` is green on the current branch
- demo mail server and demo account are opt-in
- account and folder pages render from the local SQLite mirror
- viewer, composer, and attachment flows are still partly mock-backed

## Current Backlog

### 1. Persisted Message Viewer

The message list is real, but `/emails/{emailId}/viewer` and `/emails/{emailId}/viewer/body` still render mock data.

Needed:

- add repository and service lookup by local email id
- return `404` for unknown message ids
- render viewer pages from persisted message data
- serve the body iframe from persisted stored content instead of `MockEmailData`

Blocking dependency:

- decide and implement the stored message body model

### 2. Message Body Storage Model

The sync path currently stores message metadata much more completely than full viewer-ready bodies.

Needed:

- decide which body formats are stored locally
- likely store plaintext and HTML separately when available
- keep HTML sanitization on render
- decide whether excerpt/snippet remains derived or becomes persisted

### 3. Draft Persistence And Composer Flow

Composer creation and loading are still stubbed:

- draft ids are fake
- reply and forward are mock-derived
- send and delete currently validate and redirect without persistence

Needed:

- introduce a persisted draft model
- create, load, update, delete, and queue drafts through repository and service code
- derive reply and forward drafts from real stored messages
- persist composer form data instead of treating it as transient request data

### 4. Attachment Persistence

Attachment routes exist, but attachment content and uploads are still mock-backed or in-memory only.

Needed:

- persist attachment metadata in SQLite
- store attachment content locally
- tie attachments to real messages and drafts
- replace the mock attachment endpoint with real file-backed serving

### 5. Search

`/accounts/{accountId}/search` exists structurally but still returns an empty result page.

Needed:

- implement query execution against the local mirror
- support paging consistent with mailbox browsing
- keep the result view inside the existing account page model

### 6. Home Page

The home route is real, but the template is still placeholder content.

Needed:

- replace `Hello World!` with intentional behavior
- handle zero, one, and multiple account states deliberately

### 7. Browser-Level Regression Coverage

The project has Maven tests, but it still lacks browser coverage for the SSR and dialog-heavy interaction model.

Needed:

- add Playwright smoke tests for core page rendering and navigation
- cover dialog and iframe flows that unit tests do not protect
- keep the suite focused on real browser behavior rather than screenshots

### 8. Runtime And Storage Hardening

Still worth doing:

- replace ad-hoc stdout logging with structured logging
- tighten runtime configuration defaults where local safety matters
- revisit SQLite details such as indices and schema constraints as the model solidifies

## Recommended Next Slice

If picking one product-facing task next:

1. implement message lookup by local id
2. define the stored body model
3. wire the viewer to persisted messages

That is the smallest change that removes a visible mock path without changing the overall architecture.
