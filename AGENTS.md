# AGENTS.md

## Purpose

Quicksand is a server-rendered email client prototype built as a classic multi-page application on Java 25.

Core invariants:

- the server owns routing, HTML generation, and most application state
- the browser navigates real URLs and renders full HTML documents
- JavaScript is used for enhancement, not as the primary rendering or state model
- mailbox data is mirrored locally and rendered from SQLite rather than queried from IMAP on every request
- the deployment baseline is standard Java on the JVM

## Architecture

The codebase is organized around four layers:

1. runtime wiring in the application entrypoint, with explicit composition rather than framework-heavy dependency injection
2. a local mailbox mirror backed by SQLite and maintained by sync code in `net.aggregat4.quicksand.jobs` and `net.aggregat4.quicksand.repository`
3. thin application services in `net.aggregat4.quicksand.service`
4. a Helidon-based, HTML-first web layer in `net.aggregat4.quicksand.webservice`

Keep those boundaries intact. New behavior should normally flow from repository to service to template-backed route.

## Web And UI Model

Quicksand is not a SPA. Preserve the document-oriented model:

- account, folder, search, viewer, and composer flows should remain URL-addressable
- primary responses should be HTML documents or redirects after form posts
- Pebble templates are the primary rendering system
- reusable UI fragments belong in Pebble macros/includes, not client-side component frameworks

Browser primitives are a deliberate part of the design:

- dialogs and iframes are first-class tools, not temporary hacks
- `postMessage`, `history.pushState`, and small DOM controllers are acceptable when they enhance an SSR flow
- JavaScript should assume the DOM already exists and enhance it narrowly

## Data And Interaction Conventions

- mailbox browsing should read from the local SQLite mirror
- background sync is responsible for keeping local state fresh against IMAP/SMTP sources
- list browsing is optimized for mailbox-style navigation rather than generic CRUD
- pagination and grouping behavior should preserve fast browsing and stable navigation semantics
- HTML email rendering must stay isolated and sanitized server-side

## Change Guidance

When making changes:

- do not convert the application into a SPA
- do not introduce client-side state as the source of truth for mailbox views
- prefer links, forms, redirects, and server-rendered HTML over JSON-driven browser rendering
- keep JavaScript focused on UI enhancement such as dialogs, iframe orchestration, selection state, history updates, and sizing
- prefer small controllers in `src/main/resources/static/js`; keep Pebble templates declarative and pass behavior hooks through forms, ids, and `data-*` attributes
- when enhancing forms, prefer layered behavior that still submits real forms and preserves post/redirect semantics over ad hoc client-side request construction unless there is a clear need
- preserve existing route structure unless a route change is required by the feature
- prefer isolated changes that fit the existing SSR + local-cache model

Before changing a flow, inspect the relevant webservice package, matching Pebble templates, and related static assets so the full document flow remains coherent.

## Testing, Verification, and Debugging

### Build and Test Commands

```bash
mvn test                           # unit tests only
mvn -DskipTests package            # build the jar without running tests
npm run test:e2e                   # full Playwright e2e suite (builds jar + starts server)
```

### Stale Database Trap

Quicksand persists mailbox state in a local SQLite database. The **demo mode** seeds messages on first sync, but if the database already exists it will skip seeding and show the old data. This is the most common cause of "my changes are not visible" confusion.

**Rule:** always wipe the database when you need fresh demo data.

```bash
# For manual demo server testing
rm -f target/db/quicksand.sqlite

# For e2e testing (playwright.config.mjs uses ./target/e2e-db/)
rm -rf target/e2e-db
```

### Visual Verification with Screenshots

When you add or change UI behavior, verify it visually rather than guessing from code.

**Option A — quick curl + grep (no browser needed)**

```bash
# Start a clean demo server
rm -f target/db/quicksand.sqlite
java \
  -Dserver.host=127.0.0.1 -Dserver.port=18080 \
  -Ddemo.enabled=true -Dmail_fetcher.enabled=true \
  -Duser.language=en -Duser.country=DE -Duser.timezone=Europe/Berlin \
  -jar target/quicksand.jar

# In another terminal, inspect the rendered HTML
curl -s http://127.0.0.1:18080/accounts/1 | grep -oP '(?<=class="subjectline">)[^<]+' | head -20
```

**Option B — headless Chromium screenshot**

```bash
# Start server as above, then:
chromium --headless --disable-gpu --window-size=1400,900 \
  --screenshot=/tmp/inbox.png "http://127.0.0.1:18080/accounts/1"
```

**Option C — Playwright for interactive inspection**

```bash
npx playwright test e2e/smoke.spec.js --grep "descending inbox" --reporter=line
```

### E2E Test Idiosyncrasies

- `npm run test:e2e` runs `mvn -DskipTests package` first, then starts the server on port **43173** with a **fixed clock** (`2026-03-25T09:15:00Z`). This makes temporal grouping deterministic.
- The e2e server uses `target/e2e-db/quicksand.sqlite`. If e2e tests behave oddly, wipe `target/e2e-db/`.
- Playwright's `webServer` block auto-starts and tears down the JVM process. Do **not** start a manual server on port 43173 at the same time.
- Adding new demo emails shifts group boundaries on the first page. If you add boundary seeds, check `e2e/smoke.spec.js` constants (`DESCENDING_GROUPS`, `DUPLICATE_SEARCH_COUNT`, etc.) and update them to match the new distribution.

### Background Server Gotchas

Avoid `&` backgrounding inside shell scripts when you need to inspect output. It swallows errors and leaves zombie processes. Prefer either:

1. **Foreground in one terminal**, curl from another.
2. **Let Playwright manage the server** (it handles startup, port checks, and teardown).

If you accidentally leak a server process:

```bash
pkill -f "quicksand.jar"
```
