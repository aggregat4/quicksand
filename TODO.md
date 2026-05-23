# TODO

## Session snapshot (2026-05-21, end of day)

**Branch:** `main` (ahead of `origin/main`; last commit `72feb19` — notification UX fixes).

**Local WIP (not committed):** attachments, SSE notifications, schema v7 hardening, folder-mapping persistence fixes, and related tests. `mvn test` green on this tree.

### Shipped in WIP (code done, needs commit + manual re-verify on real IMAP)

| Area | Status | Notes |
|------|--------|-------|
| **§3 incoming attachments** | Done in WIP | `ImapAttachmentExtractor`, persist to `attachments.message_id`, viewer/list hydration; named `text/html` attachments fixed |
| **§5b SSE notifications** | Done in WIP | `GET /accounts/{id}/events`, `MailboxUpdateBroadcaster`, `notifications.js` EventSource + poll fallback, read-state patches; `SseIo` quiet client disconnect |
| **§4 hardening (partial)** | Done in WIP | Schema **v7** indexes; default bind `127.0.0.1` in `application.conf` |
| **§1b folder mapping persistence** | Fixed in WIP | Single save form (selects inside POST form), `save_mappings=true`, confirm auto-detected on save, `FolderRemoteNameMatcher`, reconcile `USER_CONFIRMED` by remote name — **rebuild jar and re-save mappings once** |

### Manual verification pending (real account)

- [ ] Rebuild: `mvn -DskipTests package` then `./scripts/start-real-server.sh`
- [ ] Query **`./target/db/quicksand-real.sqlite`** (not demo DB) after save:
  ```sql
  SELECT special_use, folder_id, remote_name, status
  FROM account_folder_mappings WHERE account_id = 1;
  ```
  Expect all required roles **`USER_CONFIRMED`** with non-null `folder_id` / `remote_name`.
- [ ] Incoming attachments (docx/pdf/html) visible in viewer after sync
- [ ] SSE `/accounts/1/events` — no `Broken pipe` spam on tab close
- [ ] `npm run test:e2e` not re-run this session

---

## Verified Baseline

- Java 25 application on Helidon 4
- runnable JVM artifact is `target/quicksand.jar`
- `mvn test` green on current WIP tree (2026-05-21)
- `npm run test:e2e` was green on last committed baseline; re-run after commit
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
- **HTML email sanitization** is covered by unit tests against realistic fixtures and exercised end-to-end through demo inbox HTML emails
- **rich HTML demo emails** are available in demo mode for manual viewer testing
- **IMAP capability probe** — `./scripts/imap-probe.sh` reports server extensions and Quicksand-relevant summaries

---

## Roadmap

Priority order was **§3 attachments → §5b SSE → §4 hardening**. All three touched in WIP; **§4 remainder** is next after commit + real-IMAP verify.

| # | Slice | Goal | Status |
|---|-------|------|--------|
| ~~—~~ | ~~**§1b SPECIAL-USE folder setup UX**~~ | ~~Smoother first connect~~ | **Done** (+ mapping persistence fix in WIP) |
| ~~—~~ | ~~**§2a/b/c inbound sync**~~ | ~~CONDSTORE, QRESYNC, IDLE~~ | **Done** |
| ~~—~~ | ~~**§4a Real IMAP/SMTP TLS**~~ | ~~Connect to Gmail/Fastmail-style servers~~ | **Done** |
| ~~—~~ | ~~**§5 notifications v1**~~ | ~~Unread badges + poll strip~~ | **Done** — [`specs/new-mail-notifications.md`](specs/new-mail-notifications.md) |
| ~~1~~ | ~~**§3 incoming attachments**~~ | ~~Real attachment bytes during IMAP sync~~ | **Done (WIP)** |
| ~~2~~ | ~~**§5b SSE notifications**~~ | ~~SSE wake-up + read-state patches~~ | **Done (WIP)** |
| **3** | **§4 runtime/schema hardening** | Safer non-local deployment | **Partial (WIP)** — see below |

Probe modern servers: `./scripts/imap-probe.sh`.

---

## IMAP action sync (merged to `main`)

Local-first mailbox actions with queued IMAP replay. Spec: [`specs/imap-action-sync.md`](specs/imap-action-sync.md).

| Area | What |
|------|------|
| Schema | v3 action-sync tables + **v4** draft remote UID columns |
| Discovery | UIDVALIDITY + SPECIAL-USE on folder sync |
| Setup | Folder mapping settings UI, remote folder creation, account blocker when mappings missing |
| Local actions | Transactional enqueue; block when required mapping missing |
| Background sync | `MailboxActionSync`: read/unread, move-like, Sent append, Drafts upsert/delete |
| Recovery | Sync status view + retry/dismiss/abandon/rollback/reset |

**Deferred:** UIDPLUS COPY/delete fallback when the server lacks `UID MOVE`.

---

## §3 Incoming attachments (WIP — ready to commit)

- **Goal:** extract attachment MIME parts during `ImapStoreSync.downloadNewMessages`, persist to `attachments.message_id`, hydrate on read for list clip icon + viewer download links.
- **Reuse:** existing `attachments` table, `/attachments/{id}`, `emailviewer.peb`.
- **Shipped:** `ImapAttachmentExtractor`, `AttachmentContentHasher`, `InboundAttachment`, `saveMessageAttachments` / `findByMessageId`, `DbEmailRepository` read path, GreenMail + DB round-trip tests.
- **Fix:** named `text/html` parts (e.g. `.html` files with `Content-Disposition: inline`) now treated as attachments when they have a filename.

---

## §5 New-mail notifications

**Spec:** [`specs/new-mail-notifications.md`](specs/new-mail-notifications.md)

**v1 (committed):** unread sidebar badges, INBOX “new since view” strip, HTML fragment poll, live list prepend on folder page 1, mark-read on view, in-folder banner.

**§5b SSE (WIP — ready to commit):**

- `GET /accounts/{id}/events` — `mailbox-updated` after each account IMAP sync (`MailboxUpdateBroadcaster` ← `MailFetcher`)
- `notifications.js` — `EventSource` + 15s poll fallback / 60s backstop when SSE connected
- Read-state patches via `visibleMessageIds` on notifications fragment
- `SseIo` + tests for quiet handling of client disconnect (`Broken pipe`)

**Optional later:** Playwright SSE coverage; desktop notification opt-in UI.

---

## §1b Folder mapping setup (WIP fix)

**Previously shipped:** auto-detect SPECIAL-USE, confirm-all banner, suggested/other folder pickers, folder-settings tests.

**Bug found on real IMAP (2026-05-21):** mappings appeared to save but DB stayed `AUTO_DETECTED` / `MISSING` after restart — save POST was not reliably including `<select>` values (HTML `form=` association).

**Fix in WIP:**

- Single POST form wrapping all mapping selects (`folder-settings.peb`)
- `save_mappings=true` on Save button; reject empty/invalid POSTs
- `confirmAutoDetectedMappings()` after save; reconcile `USER_CONFIRMED` by remote name (`FolderRemoteNameMatcher`)
- Tests: `AccountFolderSettingsWebServiceTest.saveMappingsPersistsUserConfirmedAfterFolderResync`

**After deploy:** save mappings once against `./target/db/quicksand-real.sqlite`, then confirm `USER_CONFIRMED` survives restart.

---

## §4 Runtime, schema, and storage hardening

**Shipped in WIP (schema v7 + config):**

- default bind `127.0.0.1` in `application.conf` (override `-Dserver.host=0.0.0.0` for LAN/container)
- indexes: `messages(folder_id, received_date_epoch_s, id)`, unique `(folder_id, imap_uid)`, `outbound_messages(status, next_attempt_at_epoch_s)`

**Remaining:**

- SQLite/Hikari pool tuning documentation
- per-account folder uniqueness cleanup (`folders.name` legacy global unique)
- folder-scoped message UID identity enforcement
- broader `NOT NULL` / FK cascades
- replace plaintext account passwords before non-local use
- schema/constraint regression tests

---

## Backlog: real IMAP server connections

Use `./scripts/start-real-server.sh` against `config/application-local.conf`.

1. Build: `mvn -DskipTests package`
2. Copy: `cp config/application-local.conf.example config/application-local.conf`
3. Start: `./scripts/start-real-server.sh` — DB default **`target/db/quicksand-real.sqlite`**
4. `--wipe-db` when switching credentials (accounts use `INSERT OR IGNORE`)
5. Optional: `mail_fetcher.idle_enabled = true` when server supports IDLE

| Scenario | Status |
|----------|--------|
| Demo / GreenMail via `./scripts/start-test-server.sh` | Works |
| Real IMAP/SMTP via `./scripts/start-real-server.sh` (993/587 TLS) | Works |
| `./scripts/imap-probe.sh` against IMAPS 993 | Works |
| Folder mappings persist across restart | **Fixed in WIP — verify after rebuild** |
| Account UI / OAuth2 (Gmail sign-in) | Not implemented |
| Password storage | Plaintext in SQLite (pre-production) |

---

## Next session (suggested order)

1. **Commit WIP** — attachments, SSE, v7 hardening, folder-mapping fix (split or one slice as preferred)
2. **Manual real-IMAP verify** — mappings DB check, attachments, SSE console clean
3. **§4 hardening** — remaining schema/runtime items
4. **Optional:** `npm run test:e2e`; Playwright for SSE wake-up

---

## Recently completed

| When | What |
|------|------|
| WIP (uncommitted) | **§3 attachments** + html filename fix |
| WIP (uncommitted) | **§5b SSE** + read-state patches + disconnect handling |
| WIP (uncommitted) | **§4 v7 indexes** + bind default |
| WIP (uncommitted) | **Folder mapping save persistence** fix |
| `72feb19` | Notification UX: mark read on view, banner, live list insert |
| `58cf504` / `003bcb6` | Notifications v1 backend + poll strip + live list |
| `f678ff4` | QRESYNC + optional IMAP IDLE |
| `a0aec48` | Real IMAP/SMTP TLS + `start-real-server.sh` |
| `16e6946` | IMAP action sync merged |

---

## Reference: inbound IMAP sync (all shipped)

- **2a CONDSTORE** — `ImapFolderSyncEngine`, folder checkpoints
- **2b QRESYNC** — VANISHED UID handling
- **2c IDLE** — opt-in via `mail_fetcher.idle_enabled`

Refs: `ImapFolderSyncEngine.java`, `CondstoreSyncPolicy.java`, `ImapIdleWatcher.java`, `MailFetcher.java`.

Developer linting/tooling is sufficient unless new build pain appears.
