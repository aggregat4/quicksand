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
- send creates real outbound messages in a synthetic Outbox folder
- outbound messages are delivered through SMTP with persisted status and retry state
- browser and GreenMail-backed integration coverage now covers draft send, attachment handoff, SMTP delivery, retry scheduling, and IMAP round-trips

## Current Backlog

### 1. Search

`/accounts/{accountId}/search` exists structurally but still returns an empty result page.

Needed:

- implement query execution against the local mirror
- support paging consistent with mailbox browsing
- keep the result view inside the existing account page model

### 2. Home Page

The home route is real, but the template is still placeholder content.

Needed:

- replace `Hello World!` with intentional behavior
- handle zero, one, and multiple account states deliberately

### 3. Runtime And Storage Hardening

Recent work improved logging, sync behavior, paging, grouping, and deterministic test setup, but a few hardening tasks remain.

Needed:

- tighten runtime configuration defaults where local safety matters
- revisit SQLite details such as indices and schema constraints as the model solidifies
- extend regression coverage as new persisted flows land

## Recommended Next Slice

If picking one product-facing task next:

1. implement real query execution against the local mirror
2. keep search paging and sorting aligned with mailbox browsing
3. preserve the existing account-page viewer flow for result selection
4. add browser coverage for targeted and multi-page search results

That is now the clearest missing user-facing mailbox feature.
