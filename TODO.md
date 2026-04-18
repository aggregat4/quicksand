# TODO

Current verified baseline:

- Java 25 application on Helidon 4
- `mvn clean test` and `npm run test:e2e` are green on the current branch
- demo mail server and demo account are opt-in
- account and folder pages render from the local SQLite mirror
- account-wide message search runs against a local SQLite FTS index with paging, reset, server-rendered highlighting, and HTML body match highlighting inside sanitized viewer content
- persisted message viewer routes load real stored messages
- inbox paging, sorting, and temporal grouping have deterministic browser coverage
- demo inbox seeding is deterministic for tests and manual review
- drafts are persisted in SQLite, visible in a synthetic Drafts folder, and autosaved through the composer flow
- draft attachments are persisted in SQLite with content hashes and served back through real attachment routes
- send creates real outbound messages in a synthetic Outbox folder
- outbound messages are delivered through SMTP with persisted status and retry state
- browser and GreenMail-backed integration coverage now covers draft send, attachment handoff, SMTP delivery, retry scheduling, IMAP round-trips, and targeted search highlighting regressions

## Current Backlog

### 1. Developer Tooling

Needed:

- add `spotless-maven-plugin` with `google-java-format`
- enable `removeUnusedImports`
- try Error Prone as a correctness-focused compiler check
- keep this intentionally free of style-policy nitpicking tools

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

1. wire in `spotless + google-java-format + removeUnusedImports`
2. try Error Prone and keep it only if the signal is good
3. then replace the placeholder home page with deliberate account-state handling
4. keep tightening runtime defaults and SQLite constraints as persisted flows expand

That keeps the next slice focused on build hygiene before the home page and storage hardening work.
