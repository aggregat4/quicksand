# AGENTS.md

## Project constraints

Quicksand is a server-rendered email client on Java 25. Preserve these invariants:

- The server owns routes, HTML generation, and application state.
- Browser navigation uses real URLs and full HTML documents.
- JavaScript enhances existing documents; it is not the rendering, routing, or mailbox-state model.
- Mailbox views read from the local SQLite mirror, not directly from IMAP.
- Background jobs reconcile SQLite with IMAP and SMTP.
- The deployment baseline is standard Java on the JVM.

Do not turn a flow into an SPA, introduce client-side mailbox state as authoritative, or replace
links/forms/post-redirect behavior with a JSON-driven UI.

## Code map

| Area | Location | Responsibility |
|------|----------|----------------|
| Runtime composition | `net.aggregat4.quicksand.Main` | Explicit application wiring and job startup |
| Sync and delivery | `net.aggregat4.quicksand.jobs` | IMAP mirror, mailbox actions, SMTP delivery |
| Persistence | `net.aggregat4.quicksand.repository` | SQLite repositories and transaction boundaries |
| Application services | `net.aggregat4.quicksand.service` | Thin coordination between web and persistence |
| HTTP layer | `net.aggregat4.quicksand.webservice` | Helidon routes, HTML responses, redirects |
| HTML | `src/main/resources/templates` | Pebble documents, macros, and fragments |
| Browser enhancement | `src/main/resources/static/js` | Focused ES modules by load boundary |

New behavior normally flows from repository to service to template-backed route. Before changing a
flow, inspect its webservice, service/repository calls, Pebble templates, JavaScript, and tests. Do
not change only one side of a document flow without checking the others.

## Architecture references

- [`docs/frontend-architecture.md`](docs/frontend-architecture.md): module loading, import maps, and
  browser/server boundaries.
- [`docs/mailbox-sync-architecture.md`](docs/mailbox-sync-architecture.md): IMAP identity, action
  queue, reconciliation, and recovery.
- [`docs/notifications.md`](docs/notifications.md): SSE/poll notification refresh behavior.
- [`docs/runtime-database.md`](docs/runtime-database.md): SQLite, Hikari, schema, and backup rules.
- [`docs/account-credentials.md`](docs/account-credentials.md): credential-key setup and operations.

Keep durable explanations in these documents. Keep this file focused on instructions an agent must
apply while making changes.

## Implementation rules

### Server-rendered flows

- Keep account, folder, search, viewer, and composer flows URL-addressable.
- Prefer HTML responses and redirects after form posts.
- Keep reusable markup in Pebble macros/includes.
- Dialogs, iframes, `postMessage`, and `history.pushState` are valid progressive-enhancement tools.
- Preserve existing routes unless the requested behavior requires a route change.
- Sanitize HTML email bodies server-side and keep them isolated from application UI.

### JavaScript and templates

Pebble templates must remain declarative. Do not add application `<script>` blocks, inline `on*`
handlers, or macro arguments containing handler source.

- Put behavior in ES modules below `src/main/resources/static/js/`.
- Use `quicksand/...` bare specifiers from the generated import map.
- Export a focused `init…()` function and bootstrap through `lib/dom-ready.js`.
- Pass server configuration through stable IDs, names, and `data-*` attributes.
- Prefer event delegation for repeated message/folder nodes.
- Respect load boundaries: global code in `shell/`, mailbox-only code in `account/` or
  `notifications/`, standalone frame code in `iframe/`, and page-specific code in `settings/`.
- Mailbox modules load only behind `#messagelist`; composer-dialog code remains lazy.
- Notification refresh may replace only the strip and folder badges. It must not patch message rows
  or read state.
- Keyboard bindings belong in `account/keyboard-bindings.js`; key dispatch and DOM actions stay in
  the keyboard modules.

Files below `static/js/` are registered in the import map automatically.

### Mailbox and database behavior

- IMAP identity is `(account, remote mailbox, UIDVALIDITY, UID)`, never a bare UID.
- `messages.folder_id` is desired UI location; `remote_*` columns hold observed server identity.
- Message-ID is non-unique recovery evidence, not a normal deduplication key.
- Unresolved mailbox-action rows must survive message and folder mirror eviction.
- All IMAP work for one account must run through `AccountSyncCoordinator`.
- HTTP actions update SQLite and enqueue remote intent transactionally; they do not wait for IMAP.
- Schema changes require a migration, migration tests, and an update to
  `docs/runtime-database.md` when operational behavior changes.
- Repository code loading related rows should reuse the caller's `Connection` where possible; do
  not casually add nested pool checkouts.

## Verification

Run the smallest relevant check while iterating, then the appropriate suite before handoff:

```bash
mvn test                 # Java unit/integration tests and formatting checks
mvn -DskipTests package  # Build the runnable jar
npm run test:e2e         # Full Playwright suite; builds and starts the server
```

For UI changes, verify rendered HTML and behavior rather than relying on code inspection. Use a
focused Playwright test when interaction matters. A quick manual check can use a clean demo server
and `curl` or a headless Chromium screenshot.

### Persistent database trap

Demo data is seeded only into a new database. Wipe the relevant database when fresh fixtures are
required:

```bash
rm -f target/db/quicksand.sqlite
rm -rf target/e2e-db
```

The Playwright server uses `target/e2e-db/quicksand.sqlite`, port `43173`, and the fixed clock
`2026-03-25T09:15:00Z`. Do not run a manual server on that port during e2e tests. Adding demo mail
can shift pagination/group boundaries; check constants such as `DESCENDING_GROUPS` and
`DUPLICATE_SEARCH_COUNT` in `e2e/smoke.spec.js`.

Let Playwright manage its server. For manual debugging, run the JVM in the foreground and issue
requests from another terminal; shell-backgrounded servers hide startup failures and are easily
leaked. If necessary, stop a leaked instance with:

```bash
pkill -f "quicksand.jar"
```

### Real account prerequisites

The JVM needs `QUICKSAND_CREDENTIAL_KEY` or `config/credential-key` to decrypt stored IMAP/SMTP
passwords. Use the documented start scripts rather than duplicating credential setup manually.

Probe a real IMAP server when capability-dependent sync behavior changes:

```bash
./scripts/imap-probe.sh --host HOST --user USER --password PASS [--port 993] [--no-ssl]
```
