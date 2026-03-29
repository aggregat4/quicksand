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
- draft attachments are persisted in SQLite with content hashes and served back through real attachment routes

## Current Backlog

### 1. Attachment Send And Queueing

Draft attachments are now persisted, but the send path still does not project them into a queued or outbound message model.

Needed:

- carry draft attachments through the send flow
- define how queued messages reference their attachments
- keep delete, queue, and eventual delivery behavior coherent
- add browser and service coverage for the full attachment lifecycle

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

1. carry persisted draft attachments into the send flow
2. model queued-message attachments explicitly
3. keep composer, draft deletion, queueing, and delivery semantics aligned
4. add regression coverage around attachment send behavior

That is now the biggest remaining gap inside the composer/send workflow.
