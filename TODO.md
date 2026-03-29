# TODO

Current verified baseline:

- Java 25 application on Helidon 4
- `mvn test` and `npm run test:e2e` are green on the current branch
- demo mail server and demo account are opt-in
- account and folder pages render from the local SQLite mirror
- persisted message viewer routes load real stored messages
- inbox paging, sorting, and temporal grouping have deterministic browser coverage
- demo inbox seeding is deterministic for tests and manual review
- composer and attachment flows are still partly mock-backed

## Current Backlog

### 1. Draft Persistence And Composer Flow

Composer creation and loading are still stubbed:

- draft ids are fake
- reply and forward are mock-derived
- send and delete currently validate and redirect without persistence

Needed:

- introduce a persisted draft model
- create, load, update, delete, and queue drafts through repository and service code
- derive reply and forward drafts from real stored messages
- persist composer form data instead of treating it as transient request data

### 2. Attachment Persistence

Attachment routes exist, but attachment content and uploads are still mock-backed or in-memory only.

Needed:

- persist attachment metadata in SQLite
- store attachment content locally
- tie attachments to real messages and drafts
- replace the mock attachment endpoint with real file-backed serving

### 3. Search

`/accounts/{accountId}/search` exists structurally but still returns an empty result page.

Needed:

- implement query execution against the local mirror
- support paging consistent with mailbox browsing
- keep the result view inside the existing account page model

### 4. Home Page

The home route is real, but the template is still placeholder content.

Needed:

- replace `Hello World!` with intentional behavior
- handle zero, one, and multiple account states deliberately

### 5. Runtime And Storage Hardening

Recent work improved logging, sync behavior, paging, grouping, and deterministic test setup, but a few hardening tasks remain.

Needed:

- tighten runtime configuration defaults where local safety matters
- revisit SQLite details such as indices and schema constraints as the model solidifies
- extend regression coverage as new persisted flows land

## Recommended Next Slice

If picking one product-facing task next:

1. introduce persisted drafts in SQLite
2. create and load real drafts through repository and service code
3. derive reply and forward drafts from stored messages
4. save composer form state instead of treating it as transient request data

That is the biggest remaining visible mock path and the next coherent end-to-end slice.
