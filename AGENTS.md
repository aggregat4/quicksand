# AGENTS.md

## Purpose

Quicksand is a prototype email client built around a deliberate constraint:

- classic multi-page application structure
- server-side rendering for primary UI generation
- small, focused JavaScript for enhancement only
- Gmail-like perceived smoothness without turning the app into a SPA
- pure JVM deployment baseline on Java 25

The project is not trying to win by moving all state and rendering into the browser. The intended model is:

- the server owns routing, HTML generation, and most application state
- the browser navigates real URLs and renders full HTML documents
- JavaScript is used to improve interaction quality around dialogs, iframes, selection state, history updates, and sizing
- mailbox data is mirrored locally so the UI can feel fast even though the upstream system is IMAP/SMTP
- the project targets the standard Java runtime; Graal/native-image is out of scope

## Architectural Intent

The architecture is split into four layers.

### 1. Runtime wiring

`src/main/java/net/aggregat4/quicksand/Main.java`

- starts the Helidon SE web server
- creates the SQLite-backed data source
- runs schema migrations
- wires repositories, services, and route handlers manually
- bootstraps configured accounts into the local database
- starts a background mail fetcher

There is no framework-heavy dependency injection setup here. Composition is explicit and local.

### 2. Local mailbox mirror

Primary files:

- `src/main/java/net/aggregat4/quicksand/jobs/MailFetcher.java`
- `src/main/java/net/aggregat4/quicksand/jobs/ImapStoreSync.java`
- `src/main/java/net/aggregat4/quicksand/repository/DbAccountRepository.java`
- `src/main/java/net/aggregat4/quicksand/repository/DbFolderRepository.java`
- `src/main/java/net/aggregat4/quicksand/repository/DbEmailRepository.java`
- `src/main/java/net/aggregat4/quicksand/migrations/QuicksandMigrations.java`

The app keeps a local SQLite projection of mailbox state:

- `accounts`
- `folders`
- `messages`
- `actors`

That local store is the basis for browsing and paging. The intended UX model is not "query IMAP on every click". It is:

- sync in the background
- persist locally
- render quickly from SQLite

This is the main reason the app can stay server-rendered and still aim for a smooth mail-client feel.

### 3. Thin application services

Primary files:

- `src/main/java/net/aggregat4/quicksand/service/AccountService.java`
- `src/main/java/net/aggregat4/quicksand/service/FolderService.java`
- `src/main/java/net/aggregat4/quicksand/service/EmailService.java`

These are intentionally thin. They mostly expose repository-backed operations to the web layer. Keep them small unless real orchestration logic emerges.

### 4. HTML-first web layer

Primary files:

- `src/main/java/net/aggregat4/quicksand/webservice/HomeWebService.java`
- `src/main/java/net/aggregat4/quicksand/webservice/AccountWebService.java`
- `src/main/java/net/aggregat4/quicksand/webservice/EmailWebService.java`
- `src/main/java/net/aggregat4/quicksand/webservice/AttachmentWebService.java`
- `src/main/resources/templates/*.peb`

The web layer is route-oriented and template-driven. It mostly returns HTML documents or redirects after POST.

This is the core product direction. Preserve it.

## UI Model

The app is closer to "document UI with embedded islands" than to a SPA.

### Primary pages

- `/` renders the home page
- `/accounts/{accountId}` renders an account shell and defaults to the first folder
- `/accounts/{accountId}/folders/{folderId}` renders a folder view
- `/accounts/{accountId}/search` is intended to render a search result view
- `/emails/{emailId}/viewer` renders a full email viewer document
- `/emails/{emailId}/composer` renders a full composer document

### Smoothness strategy

The current design gets smoother behavior from classic browser primitives:

- dialogs for overlays
- iframes for isolated subdocuments
- targeted links/forms for loading viewer and composer content
- `history.pushState` for keeping selected mail reflected in the URL
- small JS controllers for local UI state
- server-rendered pagination and grouping

Important examples:

- the account page opens message preview in a dialog containing an iframe
- the new-mail composer opens in another dialog containing an iframe
- reply/forward are implemented as cross-frame `postMessage` events, not client-side routing
- email body HTML is rendered in a dedicated iframe and sanitized server-side

This is the defining interaction pattern of the project.

## Template And Frontend Structure

Primary files:

- `src/main/resources/templates/base.peb`
- `src/main/resources/templates/account.peb`
- `src/main/resources/templates/emailheader.peb`
- `src/main/resources/templates/emailviewer.peb`
- `src/main/resources/templates/emailcomposer.peb`
- `src/main/resources/templates/macros.peb`
- `src/main/resources/static/js/main.js`
- `src/main/resources/static/js/messageviewer.js`
- `src/main/resources/static/css/*.css`

Rules implied by the current codebase:

- Pebble templates are the primary rendering system
- templates produce complete HTML documents, not JSON-backed shells
- JavaScript should assume the DOM already exists and enhance it
- CSS owns the split-pane and dialog-heavy layout
- reusable template fragments belong in Pebble macros/includes, not in a client-side component framework

## Domain Model Highlights

Primary files:

- `src/main/java/net/aggregat4/quicksand/domain/Email.java`
- `src/main/java/net/aggregat4/quicksand/domain/EmailHeader.java`
- `src/main/java/net/aggregat4/quicksand/domain/EmailGroup.java`
- `src/main/java/net/aggregat4/quicksand/domain/EmailGroupPage.java`
- `src/main/java/net/aggregat4/quicksand/domain/GroupedPeriods.java`
- `src/main/java/net/aggregat4/quicksand/domain/Pagination.java`

Notable product intent visible in the model:

- message lists page by `(received_date_epoch_s, id)` rather than SQL offset pagination
- messages are grouped into periods like `Today`, `This Week`, `Last Week`, etc.
- the app distinguishes list/header rendering from full message rendering
- actors are modeled explicitly by role (`SENDER`, `TO`, `CC`, `BCC`)

This points to an interface optimized for mailbox browsing first, not raw message CRUD.

## Current State

What is already present:

- Helidon-based HTTP server
- SQLite persistence with migrations
- background IMAP polling and folder/message sync
- account and folder rendering from the local database
- paged message lists with date grouping
- split-pane account UI
- dialog + iframe based message preview and composer flows
- server-side sanitization for HTML email bodies
- attachment download endpoint shape
- opt-in demo mode for embedded GreenMail and seeded local experimentation

What is still clearly prototype or stub territory:

- `HomeWebService` home page is placeholder content
- `EmailWebService` still serves mock viewer/composer content rather than persisted message bodies/drafts
- search page exists structurally but is not implemented
- send/delete/archive/move actions mostly log and redirect
- attachments are mock-backed, not persisted end-to-end
- message body and excerpt storage are incomplete
- account bootstrap currently reads credentials directly from config
- several tests are stale or environment-coupled

Treat the existing code as a strong UI and architecture prototype with partial backend completion.

## Design Rules For Future Work

If you are extending this project, keep these constraints:

1. Do not convert the application into a SPA.
2. Do not introduce client-side state management as the source of truth for mailbox views.
3. Prefer server-rendered HTML, redirects, and URL-addressable pages over JSON APIs plus browser templating.
4. Keep JavaScript narrow: UI enhancement, dialog orchestration, selection state, iframe communication, sizing, optimistic polish when useful.
5. Keep mailbox browsing fast by reading from the local SQLite mirror, not directly from IMAP on every request.
6. Preserve real navigable URLs for accounts, folders, searches, composers, and viewers.
7. Prefer HTML fragment reuse through Pebble macros/includes instead of frontend frameworks.
8. When adding "dynamic" behavior, first ask whether a browser primitive plus SSR can solve it cleanly.

## How To Add Features Without Violating The Architecture

Preferred approach:

- add or extend repository support
- expose it through a thin service if needed
- render the result as Pebble HTML
- use form posts and redirects for mutations
- add minimal JS only where the experience benefits materially

Good fits for this architecture:

- better paging and search
- draft persistence
- real SMTP send queue
- cached or conditional rendering for viewer pages
- HTML fragment endpoints that still return server-rendered HTML
- keyboard navigation on top of existing URLs and dialogs

Poor fits for this architecture:

- React/Vue/Svelte frontends
- client-side router takeover
- server reduced to JSON only
- mailbox list virtualization that assumes browser-owned rendering state

## Known Drift And Risks

There is some code drift from the original direction:

- `MainTest` still references old Helidon starter endpoints that are no longer part of the app
- current tests depend on opening local sockets for GreenMail and will fail in restricted environments
- some repository and sync code still has TODOs or likely bugs around deletion and body handling
- the project mixes "real local mailbox mirror" code with "mock UI workflow" code in the email viewer/composer path

When changing behavior, be careful to distinguish:

- intentional product direction
- incomplete implementation
- stale scaffold code left from earlier experiments

## Practical Guidance For Agents

Before making changes:

- read `Main.java` first to understand the current wiring
- inspect the matching Pebble template before changing a route
- inspect the matching CSS and JS before changing UI behavior
- check whether the flow is real-data-backed or still mock-backed

When editing:

- prefer isolated changes that preserve route structure
- avoid large framework introductions
- keep pages working with plain links/forms wherever practical
- treat dialogs and iframes as first-class design tools in this repo, not as temporary hacks to be removed automatically

When documenting or proposing new work:

- describe how the feature fits the SSR + MPA + local-cache model
- explain why any added JavaScript is enhancement, not architectural takeover

## Summary

Quicksand is best understood as:

"a server-rendered, locally cached, classic-web email client prototype that uses browser primitives very deliberately to approach native-mail or Gmail-like responsiveness without becoming a SPA."

Any future work should reinforce that identity rather than flatten it into a generic modern frontend stack.
