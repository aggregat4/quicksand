# Keyboard shortcuts plan

## Goals

- Full keyboard navigability on mailbox views (list, preview, search results).
- **Gmail-default bindings** on first load, with an easy opt-out.
- Preserve SSR: shortcuts trigger the same forms, links, and dialogs as mouse interaction.
- **Declarative shortcut definitions** so bindings can become user-configurable later without rewriting dispatch logic.

## Non-goals (initial delivery)

- Star toggle (`s`), undo send (`z`), threaded `n`/`p`, starred folder (`g` + `s` for “starred”).
- User-facing rebinding UI (definitions are structured for it; settings come later).
- Shortcuts inside the composer iframe or message viewer iframe (parent mailbox only).

## Architecture

```
account/keyboard-bindings.js   ← default Gmail map (data only)
account/keyboard-shortcuts.js  ← init, context guards, dispatcher, help, opt-out
account/keyboard-focus.js      ← roving tabindex, j/k, open/close preview
account/keyboard-actions.js    ← named intents → existing POST/preview/composer flows
lib/key-sequence.js            ← chord prefix matching (g + letter)
partials/keyboard-shortcuts-help.peb
```

### Declarative binding schema

Each binding is a plain object. **Actions are named intents**, not DOM calls. The dispatcher resolves `action` through `keyboard-actions.js`; only that module knows about buttons and forms.

```javascript
{
  id: 'list.next',              // stable id for future settings / persistence
  action: 'list.next',            // intent resolved by keyboard-actions.js
  keys: 'j',                      // string or string[] for chords (e.g. ['g', 'i'])
  modifiers: { shift: false },    // optional; omit = no extra modifier requirements
  contexts: ['mailbox'],          // when active (see below)
  label: 'Next message',          // help dialog
  category: 'Navigation'          // help grouping
}
```

Future configurability:

1. Load `DEFAULT_BINDINGS` (or server-provided JSON) into a `Keymap` object.
2. Persist user overrides in SQLite or `localStorage` keyed by `id`.
3. Merge overrides onto defaults; dispatcher unchanged.

### Contexts

| Context   | Meaning |
|-----------|---------|
| `mailbox` | Account page with `#messagelist` (inbox, folders, search). |
| `drafts`  | Drafts folder; `open` opens composer instead of preview. |
| `preview` | `#messagepreview` is open. |

Guards (shortcuts ignored):

- Focus in `input`, `textarea`, `select`, or `contenteditable`.
- Composer dialog (`#newmail-composer-dialog`) open.
- Move dialog open (except `Escape`).

Global opt-out: `localStorage['quicksand.keyboardShortcutsEnabled'] === 'false'`.

### Default Gmail bindings (phase 1–2)

| Keys | Action intent | Notes |
|------|---------------|-------|
| `j` / `k` | `list.next` / `list.prev` | Roving focus on `.emailheader` |
| `o`, `enter` | `list.open` | Preview or draft composer |
| `u` | `preview.back` | Close preview, return to list |
| `escape` | `ui.dismiss` | Close topmost dialog / preview |
| `/` | `search.focus` | Focus `#searchemailinput` |
| `x` | `selection.toggle` | Toggle row checkbox |
| `e` | `message.archive` | POST selection form |
| `#` | `message.delete` | POST selection form |
| `!` | `message.spam` | POST selection form |
| `Shift+i` | `message.markRead` | |
| `Shift+u` | `message.markUnread` | |
| `c` | `compose.new` | Lazy-load composer dialog |
| `r` | `compose.reply` | Active or focused message |
| `f` | `compose.forward` | Active or focused message |
| `?` | `help.show` | Shortcuts dialog |
| `g` `i` | `go.inbox` | Sidebar `data-folder-special-use` |
| `g` `t` | `go.sent` | |
| `g` `d` | `go.drafts` | |
| `g` `a` | `go.archive` | |
| `g` `b` | `go.spam` | Maps to JUNK folder |
| `v` | `message.move` | Open move dialog |

Deferred: `s` (star), `z` (undo), `n`/`p` (thread), `g`+`s` (starred), `[`/`]` (page).

## Template / server hooks

- `#messagelist` — list container (existing).
- `#searchemailinput` — search field (existing).
- `#folderlist a[data-folder-special-use="inbox|sent|…"]` — go-chords (added on sidebar links).
- `<dialog id="keyboard-shortcuts-help">` — help overlay (account page).
- `main[data-selected-email-id]` — restore focus on load (existing).

## Phased delivery

### Phase 1 — Foundation

- Roving focus, `j`/`k`/`o`/`Enter`/`Esc`/`u`, `/` → search.
- `:focus-visible` styles on list rows.
- E2e: navigation + search focus.

### Phase 2 — Actions + help

- Selection and message actions (`x`, `e`, `#`, `!`, read/unread).
- Compose / reply / forward (`c`, `r`, `f`).
- Help dialog (`?`), `localStorage` opt-out.
- E2e: toggle selection, help, opt-out.

### Phase 3 — Go chords + move

- `data-folder-special-use` on sidebar links.
- `g` chords, `v` move dialog.

### Phase 4 — Gaps

- Star (`s`) when server toggle exists.
- Undo, pagination shortcuts, settings UI for custom keymaps.

## Success criteria

- Mailbox usable without mouse: move, open, back, search, select, archive/delete/spam, compose/reply/forward.
- No inline script in Pebble; behavior in `static/js/account/`.
- Bindings live in one declarative module; dispatcher reads data only.
- E2e covers core keys; `mvn test` and Playwright stay green.

## Files touched

| Area | Files |
|------|-------|
| Docs | `docs/keyboard-shortcuts-plan.md` |
| JS | `keyboard-bindings.js`, `keyboard-shortcuts.js`, `keyboard-focus.js`, `keyboard-actions.js`, `lib/key-sequence.js` |
| Templates | `account.peb`, `partials/keyboard-shortcuts-help.peb` |
| Java | `SidebarFolderLink`, `AccountPageRenderer` |
| CSS | `account.css` |
| Tests | `e2e/keyboard-shortcuts.spec.js` |
| Policy | `AGENTS.md` (keyboard module row) |
