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
- initial IMAP sync bulk-fetches UIDs, selects display/search body parts with `BODY.PEEK[...]`, stores new messages transactionally, and avoids one slow body materialization per message
- account-wide message search runs against a local SQLite FTS index with paging, reset, server-rendered highlighting, and HTML body match highlighting inside sanitized viewer content
- persisted message viewer routes load real stored messages
- inbox paging, sorting, and temporal grouping have deterministic browser coverage
- demo inbox seeding is deterministic for tests and manual review
- drafts are persisted in SQLite, visible in a synthetic Drafts folder, and autosaved through the composer flow
- draft attachments are persisted in SQLite with content hashes and served back through real attachment routes
- send creates real outbound messages in a synthetic Outbox folder
- outbound messages are delivered through SMTP with persisted status and retry state
- browser and GreenMail-backed integration coverage covers draft send, attachment handoff, SMTP delivery, retry scheduling, IMAP round-trips, targeted search highlighting regressions, and common MIME body-selection shapes

## Current Backlog

### 1. Mailbox Interaction Gaps

Several UI affordances exist before their backing behavior is complete.

Needed:

- implement or deliberately hide unsupported bulk message actions such as archive, delete, mark read/unread, spam, and move
- decide how much IMAP server-side state should be updated from local mailbox actions
- extract and persist incoming message attachments during IMAP sync so stored messages can expose real downloaded attachments
- improve IMAP sync beyond the current naive folder/message scan only when real accounts expose measurable performance or correctness problems

### 2. Runtime, Schema, And Storage Hardening

The current defaults are acceptable for a local prototype, but they should be made explicit before treating Quicksand as a non-local or long-lived mail client.

Needed:

- decide safe default bind behavior for local runs versus Docker; `0.0.0.0` is convenient for containers but broad for direct local use
- resolve the SQLite/Hikari pooling question in `Main.createDataSource` or document the intended connection setup
- add indexes for the query paths now known to matter, including IMAP UID lookup, folder paging/sorting, actor lookup by message, and queued outbound retry scanning
- revisit folder uniqueness; folder names should likely be unique per account rather than globally unique
- store IMAP UIDVALIDITY and make UID lookups folder/validity-aware before relying on UIDs as durable identity
- add `NOT NULL`, uniqueness, and foreign-key cascade constraints where application invariants are now clear
- replace plaintext account password storage with an explicit local secret-storage strategy before non-local use; recoverable mail credentials cannot simply be bcrypt-hashed
- add targeted regression coverage for schema constraints, multi-account folders, IMAP UID behavior, and deletion/cascade behavior

## Recommended Next Slice

If picking one product-facing task next:

1. choose one mailbox action slice and wire it through repository/service/SSR routes
2. add incoming attachment extraction/persistence when message attachments become the next mail-reading slice
3. handle runtime/schema/storage hardening incrementally after product-facing mailbox behavior, unless a concrete persistence issue appears

Developer linting/tooling is sufficient for now; do not make tooling the next primary slice unless a new build pain appears.
