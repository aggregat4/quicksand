# TODO

Current verified baseline:

- Java 25 application on Helidon 4
- `mvn clean test` and `npm run test:e2e` are green on the current branch
- demo mail server and demo account are opt-in
- account and folder pages render from the local SQLite mirror
- persisted message viewer routes load real stored messages
- inbox paging, sorting, and temporal grouping have deterministic browser coverage
- demo inbox seeding is deterministic for tests and manual review
- drafts are persisted in SQLite, visible in a synthetic Drafts folder, and autosaved through the composer flow

## Current Backlog

### 1. Attachment Persistence

Attachment routes exist, but attachment content and uploads are still mock-backed or in-memory only.

Needed:

- persist attachment metadata in SQLite
- store attachment content locally
- tie attachments to real messages and drafts
- replace the mock attachment endpoint with real file-backed serving

### 2. Search

`/accounts/{accountId}/search` exists structurally but still returns an empty result page.

Needed:

- implement query execution against the local mirror
- support paging consistent with mailbox browsing
- keep the result view inside the existing account page model

### 3. Home Page

The home route is real, but the template is still placeholder content.

Needed:

- replace `Hello World!` with intentional behavior
- handle zero, one, and multiple account states deliberately

### 4. Runtime And Storage Hardening

Recent work improved logging, sync behavior, paging, grouping, and deterministic test setup, but a few hardening tasks remain.

Needed:

- tighten runtime configuration defaults where local safety matters
- revisit SQLite details such as indices and schema constraints as the model solidifies
- extend regression coverage as new persisted flows land

## Recommended Next Slice

If picking one product-facing task next:

1. persist attachment metadata and content for drafts
2. wire uploaded attachments into the existing composer flow
3. serve persisted attachments back through the attachment routes
4. keep the draft and queued-message paths coherent once attachments exist

That is now the biggest remaining visible mock path inside the composer/send workflow.
