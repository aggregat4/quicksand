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

### 1. IMAP Body Part Sync Refactor

Initial IMAP sync now fetches metadata quickly and stores new messages transactionally, but message preparation is still slow because body extraction materializes message content one message at a time. The long-term fix is to select and fetch only the display/search body part instead of fetching or materializing whole messages.

Implementation checklist:

- [ ] inspect Angus IMAP APIs for body structure traversal and `BODY.PEEK[section]`-style selected-part fetches
- [ ] move current body extraction out of `ImapStoreSync` into a focused IMAP body extraction component
- [ ] preserve current behavior behind the new component before changing fetch semantics
- [ ] implement MIME body selection rules:
  - [ ] use direct `text/plain` messages
  - [ ] use direct `text/html` messages
  - [ ] prefer HTML over plain text inside `multipart/alternative`
  - [ ] recursively handle nested multipart containers
  - [ ] ignore attachment-disposition parts for message body selection
  - [ ] avoid treating attachment contents as searchable/viewer body text
- [ ] fetch the selected text/html or text/plain body part with a non-mutating IMAP fetch so messages are not marked read
- [ ] decode selected body content with the declared charset and transfer encoding
- [ ] preserve stored `plainText`, body, excerpt, actors, dates, flags, and UID behavior
- [ ] add GreenMail-backed coverage for:
  - [ ] plain text messages
  - [ ] HTML-only messages
  - [ ] multipart alternative plain+HTML messages
  - [ ] multipart mixed body+attachment messages
  - [ ] nested mixed/alternative messages
  - [ ] attachment content not becoming the stored body or excerpt
  - [ ] UID stability across sync and flag updates
- [ ] rerun `./scripts/start-test-server.sh` and verify the preparation phase no longer scales with one slow body fetch per message
- [ ] decide which temporary sync timing logs should remain at info level, move to debug, or be removed

### 2. Runtime And Storage Hardening

Recent work improved logging, sync behavior, paging, grouping, deterministic test setup, build hygiene, and home-page routing, but a few hardening tasks remain.

Needed:

- tighten runtime configuration defaults where local safety matters
- revisit SQLite details such as indices and schema constraints as the model solidifies
- review account credential storage before treating the app as anything beyond a local prototype
- keep expanding regression coverage as new persisted flows land

### 3. Mailbox Interaction Gaps

Several UI affordances exist before their backing behavior is complete.

Needed:

- implement or deliberately hide unsupported bulk message actions such as archive, delete, mark read/unread, spam, and move
- decide how much IMAP server-side state should be updated from local mailbox actions
- extract and persist incoming message attachments during IMAP sync
- improve IMAP sync beyond the current naive folder/message scan when performance or correctness requires it

## Recommended Next Slice

If picking one product-facing task next:

1. finish the IMAP body part sync refactor so startup and first sync do not perform one slow body materialization per message
2. then choose one mailbox action slice and wire it through repository/service/SSR routes
3. keep runtime/storage hardening incremental and tied to concrete persisted flows
4. expand home-page coverage for zero-account and multi-account startup configurations when config-driven web test coverage is broadened

Developer linting/tooling is sufficient for now; do not make tooling the next primary slice unless a new build pain appears.
