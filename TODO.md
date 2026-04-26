# TODO

Current verified baseline (validated 2026-04-26):

- Java 25 application on Helidon 4
- runnable JVM artifact is `target/quicksand.jar`
- `mvn clean test` is green
- `npm run test:e2e` is green
- Maven enforces the Java/Maven baseline
- Spotless with `google-java-format` and `removeUnusedImports` is wired into the Maven validate phase
- Error Prone is wired as a warning-only correctness check with a small selected rule set
- demo mail server and demo account are opt-in
- the home route has deliberate account-state handling: zero accounts render an empty state, one account redirects to that mailbox, and multiple accounts render a large account picker
- account and folder pages render from the local SQLite mirror
- account-wide message search runs against a local SQLite FTS index with paging, reset, server-rendered highlighting, and HTML body match highlighting inside sanitized viewer content
- persisted message viewer routes load real stored messages
- inbox paging, sorting, and temporal grouping have deterministic browser coverage
- demo inbox seeding is deterministic for tests and manual review
- drafts are persisted in SQLite, visible in a synthetic Drafts folder, and autosaved through the composer flow
- draft attachments are persisted in SQLite with content hashes and served back through real attachment routes
- send creates real outbound messages in a synthetic Outbox folder
- outbound messages are delivered through SMTP with persisted status and retry state
- browser and GreenMail-backed integration coverage covers draft send, attachment handoff, SMTP delivery, retry scheduling, IMAP round-trips, and targeted search highlighting regressions

## Current Backlog

### 1. Runtime And Storage Hardening

Recent work improved logging, sync behavior, paging, grouping, deterministic test setup, build hygiene, and home-page routing, but a few hardening tasks remain.

Needed:

- tighten runtime configuration defaults where local safety matters
- revisit SQLite details such as indices and schema constraints as the model solidifies
- review account credential storage before treating the app as anything beyond a local prototype
- keep expanding regression coverage as new persisted flows land

### 2. Mailbox Interaction Gaps

Several UI affordances exist before their backing behavior is complete.

Needed:

- implement or deliberately hide unsupported bulk message actions such as archive, delete, mark read/unread, spam, and move
- decide how much IMAP server-side state should be updated from local mailbox actions
- extract and persist incoming message attachments during IMAP sync
- improve IMAP sync beyond the current naive folder/message scan when performance or correctness requires it

## Recommended Next Slice

If picking one product-facing task next:

1. choose one mailbox action slice and wire it through repository/service/SSR routes
2. keep runtime/storage hardening incremental and tied to concrete persisted flows
3. expand home-page coverage for zero-account and multi-account startup configurations when config-driven web test coverage is broadened

Developer linting/tooling is sufficient for now; do not make tooling the next primary slice unless a new build pain appears.
