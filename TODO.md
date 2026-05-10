# TODO

## Verified Baseline (2026-04-26)

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
- **HTML email sanitization** is covered by unit tests against realistic fixtures (newsletter, malicious vectors, styled inline CSS) and exercised end-to-end through demo inbox HTML emails
- **rich HTML demo emails** are available in demo mode for manual viewer testing (product launch digest, summer sale, flight confirmation, monthly invoice, security alert)

## Current Backlog

### 1. Mailbox Interaction Gaps

Several UI affordances exist before their backing behavior is complete.

**Confirmed still needed:**

- **decide how much IMAP server-side state should be updated from local mailbox actions**. Currently no local action propagates back to the IMAP server. This applies uniformly to read/unread, delete, archive, spam, and move. `EmailWebService.emailActionHandler` now has a dedicated TODO for this gap.
- **extract and persist incoming message attachments during IMAP sync**. `ImapStoreSync.downloadNewMessages` hardcodes `Collections.emptyList()` for attachments and skips `Part.ATTACHMENT` dispositions in `ImapBodyExtractor`. Stored messages therefore never expose real downloaded attachments.
- **improve IMAP sync beyond the current naive folder/message scan**. The codebase has commented-out QRESYNC/CONDSTORE paths and TODOs for `UIDVALIDITY` tracking, but only the naive UID scan is active.

### 2. Runtime, Schema, And Storage Hardening

The current defaults are acceptable for a local prototype, but they should be made explicit before treating Quicksand as a non-local or long-lived mail client.

**Confirmed still needed:**

- **decide safe default bind behavior** for local runs versus Docker. `application.conf` sets `host: "0.0.0.0"`, which is convenient for containers but broad for direct local use.
- **resolve the SQLite/Hikari pooling question** in `Main.createDataSource` or document the intended connection setup. The commented-out `SQLiteDataSource` path and the `TODO` are still there.
- **add indexes** for the query paths now known to matter. `QuicksandMigrations` has an explicit `TODO index on imap_uid since we do lookups there`. Other hot paths include folder paging/sorting (`received_date_epoch_s, id`), actor lookup by message, and queued outbound retry scanning.
- **revisit folder uniqueness**. The schema declares `folders(name TEXT UNIQUE)`, making folder names globally unique rather than per-account unique.
- **store IMAP UIDVALIDITY** and make UID lookups folder/validity-aware before relying on UIDs as durable identity. Currently `last_seen_uid` is stored per folder but `UIDVALIDITY` is not.
- **add `NOT NULL`, uniqueness, and foreign-key cascade constraints** where application invariants are now clear. The migration has a `TODO consider adding NOT NULL constraints`. Some tables (e.g. `messages.folder_id`, `messages.imap_uid`) still allow NULLs that the application never expects.
- **replace plaintext account password storage** with an explicit local secret-storage strategy before non-local use. `QuicksandMigrations` has `TODO: Store password bcrypt encrypted and salted`. Recoverable mail credentials cannot simply be bcrypt-hashed, so this needs a real design (e.g. OS keychain, master-password-encrypted store).
- **add targeted regression coverage** for schema constraints, multi-account folders, IMAP UID behavior, and deletion/cascade behavior.

## Recently Completed (since last review)

1. **Mark read/unread wired end-to-end** — `EmailRepository.updateRead`, `EmailService.updateRead`, and `EmailWebService.emailActionHandler` now handle `email_action_mark_read` / `email_action_mark_unread` for both per-email and bulk selection. Template names normalized across `emailheader.peb`, `account.peb`, and `emailviewer.peb`. Viewer toolbar now shows the correct read/unread button based on current state. `InMemoryEmailRepository` made public for reuse; new `EmailServiceTest` verifies the flag flip leaves `starred` untouched.
2. **Bug fix: missing `executeUpdate()` in `DbEmailRepository.updateFlags` and `updateRead`** — `withPreparedStmtConsumer` requires the consumer to call `executeUpdate()`, which was omitted in both flag update methods. This was caught by the new integration test and fixed before production use.
3. **Test coverage for mark read/unread** — New `EmailWebServiceActionTest` integration test starts a minimal Helidon server, seeds emails in SQLite, and verifies that POSTs to `/emails/selection` return 303 redirects and actually update the `read` column for single and bulk selections. Two new Playwright e2e tests verify bulk toolbar and per-email hover actions update the `.read` CSS class after redirect.
4. **HTML sanitization test coverage** — `HtmlSearchHighlighterTest` expanded from 2 → 8 tests with three realistic fixture files (`newsletter.html`, `malicious.html`, `styled.html`). New `EmailWebServiceSanitizationTest` exercises the production `NO_IMAGES_POLICY` and `IMAGES_POLICY` against real HTML.
5. **Rich HTML demo emails** — four new visually complex demo emails added to `GreenmailUtils` boundary seeds (summer sale, flight confirmation, monthly invoice, security alert). They use table-based layouts, inline CSS, and placeholder images for end-to-end viewer testing.
6. **Archive, spam, and move wired end-to-end** — mailbox action handling now moves archived messages to a local `Archive` folder, spammed messages to a local `Spam` folder, and selected messages to the chosen target folder. Integration coverage verifies redirects, folder count changes, and cross-account move rejection.

## Recommended Next Slice

If picking one product-facing task next:

1. **decide the IMAP propagation model for local mailbox actions** so read/unread, delete, archive, spam, and move do not get undone or conflict on the next sync
2. **add incoming attachment extraction/persistence** when message attachments become the next mail-reading slice
3. **handle runtime/schema/storage hardening incrementally**, especially folder uniqueness and UIDVALIDITY, before treating multi-account local state as durable

Developer linting/tooling is sufficient for now; do not make tooling the next primary slice unless a new build pain appears.
