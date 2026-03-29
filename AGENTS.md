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
