# Frontend modules refactor plan

Status: **planned** (not yet implemented)

This document captures the agreed direction for reorganizing Quicksand client JavaScript into ES modules without a build step, and for simplifying live inbox notifications to a banner + navigation reload model.

Related policy after implementation: update the **JavaScript Policy** section in [`AGENTS.md`](../AGENTS.md) to describe the module layout and import-map loading model.

---

## Goals

- Keep Quicksand a **server-rendered MPA** — JavaScript enhances existing HTML; it does not own mailbox state or routing.
- Replace the monolithic `main.js` with **small ES modules** and explicit imports.
- Load scripts via **`type="module"`** and a server-generated **import map** (no bundler).
- **Lazy-load account-page code** on account views only (`import()`), so settings and other pages avoid downloading message-list logic.
- Simplify notifications: show an inbox strip and update folder badges via poll/SSE; **do not** insert message rows or patch read state in the live list. Users see new mail by following the strip link (full navigation / reload).

Non-goals for this refactor:

- Web components
- Stimulus or other client frameworks
- A bundler (esbuild, Vite, etc.)
- Converting preview/composer iframes into inline shell UI

---

## Decisions (locked in)

| Question | Decision |
|----------|----------|
| How should the user act on “new mail”? | **Navigation link** on the notification strip (existing inbox href). No separate “Refresh” button. Full page load is acceptable and preferred over in-place list surgery. |
| Update folder unread badges without reload? | **Yes.** Poll fragment continues to patch sidebar badge counts. |
| Account-only code loading | **Yes.** Use dynamic `import()` from the shell entry when `body.accountpage` is present. |
| Import map maintenance | **Java helper** that discovers modules under `static/js/` and emits import-map JSON with hashed URLs via `StaticAssetRegistry`. |

---

## Current baseline (~1,160 lines)

| File | Lines | Notes |
|------|------:|-------|
| `main.js` | ~551 | Account shell, preview, scroll, mark-read, composer dialog, postMessage hub |
| `notifications.js` | ~310 | SSE + poll + live list insertion + read-state patch + badges + strip |
| `emailcomposer.js` | ~200 | Draft autosave (keep as iframe module) |
| Others | ~100 | Thin page bootstraps |

Expected outcome after refactor:

- **Similar total line count** for the module split alone (structure, not shrinkage).
- **~170–250 lines removed** once live list insertion and read-state patching are dropped (client + server template/Java + one e2e scenario).

---

## Target module layout

```
src/main/resources/static/js/
  lib/
    dom-ready.js              # onceDOMReady(fn)
    account-context.js        # getAccountId() from data-account-id

  shell/
    app.js                    # Shell entry (loaded from base.peb)
    header.js                 # New mail, close composer, folder-list toggle
    bfcache.js                # pageshow → reload when persisted
    post-message.js           # iframe postMessage hub (queued, reply, forward, close)

  account/
    index.js                  # Account entry: wires account/* inits
    message-list.js           # Header clicks, checkbox selection, select-all
    message-preview.js        # Preview dialog, selectedEmailId URL, close
    mark-read.js              # POST /read dedupe, initOpenMessageReadState
    scroll-persist.js         # sessionStorage scroll save/restore
    email-actions.js          # Toolbar enable/disable, selection form prep
    composer-host.js          # createEmailAndShowComposer, dialog open/close

  iframe/
    composer.js               # (from emailcomposer.js)
    viewer.js                 # (from messageviewer.js)
    queued.js                 # (from email-queued.js)

  notifications/
    index.js                  # SSE + poll entry (account pages only)
    apply-strip.js            # Replace #notification-strip
    apply-badges.js           # Patch folder unread badges in sidebar
    desktop-notify.js         # Optional: Notification API when tab hidden

  settings/
    sync-status.js
    notifications-pref.js
```

Conventions:

- One exported `init…()` (or `init`) per module.
- Configuration comes from the DOM (`data-*`, `id`, button `name`) — not from inline scripts.
- Cross-module imports use bare specifiers mapped in the import map (e.g. `quicksand/account/mark-read.js`).
- Shared bootstrap: `lib/dom-ready.js` replaces repeated `DOMContentLoaded` blocks.

---

## Loading model

### `base.peb` (all pages that extend it)

```html
<script type="importmap">{{ importMapJson | raw }}</script>
<script type="module" src="{{ asset('/js/shell/app.js') }}"></script>
```

### `shell/app.js`

Always runs on base-layout pages:

1. `initRuntime()` / `getAccountId()`
2. `initHeader()` — global header controls
3. `initBackForwardCache()`
4. `initPostMessageHub()` — composer/viewer iframe messages

If `document.body.classList.contains('accountpage')`:

```js
import('quicksand/account/index.js').then((m) => m.initAccountPage())
import('quicksand/notifications/index.js').then((m) => m.initNotifications(getAccountId()))
```

### Iframe documents (standalone HTML, no base.peb)

- `emailviewer.peb` → `type="module"` + `iframe/viewer.js`
- `emailcomposer.peb` → `iframe/composer.js`
- `emailqueued.peb` → `iframe/queued.js`

### Settings / sync-status

Keep page-specific modules in `{% block additionalScripts %}` with `type="module"`.

---

## Import map (Java-generated)

Add a helper (e.g. `ImportMapRenderer` or extend `StaticAssetRegistry`) that:

1. Scans `classpath:static/js/**/*.js` (same source as asset registry).
2. Builds a map from bare specifier → hashed URL:

   | Specifier | URL |
   |-----------|-----|
   | `quicksand/lib/dom-ready.js` | `{{ asset('/js/lib/dom-ready.js') }}` |
   | `quicksand/account/index.js` | `{{ asset('/js/account/index.js') }}` |
   | … | … |

3. Exposes JSON for Pebble, e.g. `importMapJson` in the base template context (or a Pebble function `importMap()`).

Specifier rule: **`quicksand/` + path relative to `static/js/`** (e.g. file `static/js/account/mark-read.js` → `quicksand/account/mark-read.js`).

Import map is rendered inline in `<script type="importmap">` on every base-layout page. Entry scripts and dynamic imports both resolve through it.

### Static asset routing prerequisite

`StaticAssetWebService` must serve **nested paths** under `/js/` (e.g. `/js/account/message-list.js`), not only a single `{fileName}` segment. The registry already indexes nested files; extend HTTP routing (e.g. `/{*path}`) before rolling out modules.

Unversioned URLs (`/js/foo.js` without `?v=`) may continue to work for development; production templates should use import-map entries that include `?v=` from `StaticAssetRegistry.url()`.

---

## Notifications: simplified behavior

### Keep

- SSE `/accounts/{id}/events` → triggers poll (with reconnect + backstop interval).
- GET `/accounts/{id}/notifications?folderId=…` returning an HTML fragment (`partials/notifications.peb`).
- **Notification strip** — replace `#notification-strip` in the header when inbox has new mail since last view. Strip remains a **link to inbox** (`notificationSummary.inboxHref`); clicking navigates away and loads a fresh SSR list.
- **Folder unread badges** — patch `.folder-unread-badge` counts in the sidebar without full page reload.
- **Desktop notifications** (optional) — when tab hidden and user opted in on settings; body text from strip.
- Skip or throttle poll while composer dialog is open (reasonable guard).

### Remove (client)

- `applyMessageListUpdates`, group header insertion, message row insertion
- `applyReadStateUpdates` / `.read-state-update` patching
- `listCursorParams()`, `visibleMessageIdParams()` on poll URL
- Pagination status (`#pagination-status`) count patching

### Remove (server)

In `AccountPageRenderer.renderNotifications` and `partials/notifications.peb`:

- Query params: `listCursorMessageId`, `listCursorReceived`, `visibleMessageIds`
- `newMessageGroups` / `#messagelist-updates` block (including nested `emailheader.peb` renders)
- `readStateUpdates` / `#read-state-updates`
- `emailService.getMessagesNewerThan(…)` on the notifications poll path
- `emailService.getReadStatesForMessages(…)` on the notifications poll path

Fragment payload shrinks to: **strip + folder badges** (and hidden strip node when count is zero).

### UX

- User on Drafts sees strip appear after SSE/poll when inbox has new mail.
- User clicks strip → navigates to inbox → full SSR page with updated list.
- Folder badges update in place so sidebar counts stay roughly current without reloading the whole document.

### Tests to update

| Test | Action |
|------|--------|
| `e2e/notifications.spec.js` — SSE strip | **Keep** |
| `e2e/notifications.spec.js` — live row insert while preview open | **Remove or rewrite** (no longer a product requirement) |
| `e2e/message-viewer.spec.js` — `serverReadState` via notifications poll | **Change** to another read probe (direct DB not available in e2e; use rendered HTML after navigation, selection action, or a dedicated small endpoint if needed) |
| `DocumentCacheControlTest` — notifications fragment | **Keep**; still dynamic HTML |

---

## `main.js` decomposition map

| Current responsibility | Target module |
|------------------------|---------------|
| `initQuicksandRuntime` | `lib/account-context.js` |
| Header, folder toggle | `shell/header.js` |
| `initAccountPage`, messagelist click/change | `account/message-list.js` |
| Scroll persistence | `account/scroll-persist.js` |
| Mark-read, `initOpenMessageReadState` | `account/mark-read.js` |
| Selection toolbar, form submit prep | `account/email-actions.js` |
| Preview dialog, URL `selectedEmailId` | `account/message-preview.js` |
| Composer dialog fetch/open/close | `account/composer-host.js` |
| Drafts page composer open | `account/index.js` (or small `drafts.js`) |
| `window` postMessage listener | `shell/post-message.js` |
| BFCache recovery | `shell/bfcache.js` |

Delete `main.js` once `shell/app.js` + `account/index.js` cover all call sites.

---

## Migration order (recommended PR sequence)

1. **Static asset nested routing** — `/js/**` serves correctly; add a test for `/js/account/index.js`.
2. **Import map generator** — Java helper + Pebble exposure; wire into `base.peb` alongside existing script (canary: one trivial module).
3. **Shell extraction** — `lib/dom-ready.js`, `shell/app.js`, move header/bfcache/postMessage out of `main.js`; switch `base.peb` to `type="module"`.
4. **Account module split** — dynamic import; move `main.js` body into `account/*`; delete `main.js`.
5. **Iframe modules** — rename/move to `iframe/`; update viewer/composer/queued templates.
6. **Settings modules** — `settings/sync-status.js`, `settings/notifications-pref.js` (optional path cleanup).
7. **Notifications simplification** — client trim, server fragment trim, partial template trim, e2e updates.
8. **AGENTS.md** — extend JavaScript Policy with module layout, import map, and notification model.

Each step should leave the app runnable; avoid a single big-bang PR.

---

## Acceptance criteria

- [ ] No inline JavaScript or `on*` attributes in Pebble templates (existing policy holds).
- [ ] No global `function init()` files except through explicit module exports.
- [ ] Account settings page does not download `account/message-list.js` (verify in Network tab).
- [ ] Import map lists every module under `static/js/` with correct `?v=` hashes.
- [ ] New mail while on another folder shows strip; clicking strip opens inbox with new messages visible after full load.
- [ ] Folder sidebar badges update after poll without full reload.
- [ ] Preview, composer, mark-read, scroll persistence, and selection toolbar still work.
- [ ] `npm run test:e2e` passes (after updating notifications/live-insert tests).

---

## Open questions (defer unless implementation hits a snag)

- Whether to merge `email-queued.js` postMessage into a shared `iframe/signals.js` (cosmetic).
- Whether desktop notifications stay in the poll module or only run when settings opt-in is present (current behavior is fine to preserve).
- Exact Pebble API name for import map (`importMapJson` context variable vs `importMap()` function).
