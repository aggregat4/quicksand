# Frontend architecture

## Scope

Quicksand is a server-rendered multi-page application. The server owns routes, HTML, and mailbox
state; browser code enhances documents that are already usable as links and forms. There is no
bundler and no client-side component or routing framework.

## Loading boundaries

JavaScript is split first by where it loads and then by behavior:

| Directory | Load boundary | Responsibility |
|-----------|---------------|----------------|
| `shell/` | Every page extending `base.peb` | Header controls, bfcache recovery, iframe messaging |
| `account/` | Pages containing `#messagelist` | Message list, preview, selection, actions, keyboard navigation |
| `notifications/` | Same mailbox gate as `account/` | SSE/poll wake-up, notification strip, folder badges |
| `iframe/` | Standalone viewer, composer, and queued documents | Behavior isolated inside each iframe |
| `settings/` | The corresponding settings page | Page-specific settings behavior |
| `lib/` | Imported by other modules | Shared helpers without independent bootstrap |

`base.peb` emits the import map and loads `shell/app.js` as the single global entry point. The shell
initializes global behavior, then dynamically imports account and notification entries only when
`#messagelist` exists. Composer dialog code is loaded on first use by the shell header or message
hub. Iframe and settings templates load their focused entries directly.

## Import map and static assets

`ImportMapRenderer` enumerates every `.js` asset registered below `static/js/` and maps
`quicksand/<relative path>` to its hashed `?v=` URL. Listing a module in the import map does not
download it; static and dynamic imports determine the actual request graph.

Modules use bare specifiers such as:

```javascript
import { onceDOMReady } from 'quicksand/lib/dom-ready.js'
```

`StaticAssetRegistry` is the common source for import-map URLs and normal template asset URLs, so
both receive the same content hash and cache behavior.

## Module conventions

- Pebble templates contain declarative markup, external module tags, and stable DOM hooks; they do
  not contain application scripts or inline event handlers.
- Each behavior module exports one `init…()` function, or a small focused set of named exports.
- Configuration crosses the server/browser boundary through element IDs, names, and `data-*`
  attributes.
- Controllers prefer event delegation from a page container when many repeated nodes share the
  behavior.
- Browser enhancements activate existing links, forms, dialogs, and iframes. They do not become the
  authoritative mailbox state.

Keyboard shortcuts follow the same boundary. Bindings are declarative data in
`account/keyboard-bindings.js`; `keyboard-shortcuts.js` dispatches named intents and
`keyboard-actions.js` maps those intents to the existing SSR controls.

See [`notifications.md`](notifications.md) for the mailbox notification refresh path. The detailed
coding policy and current module inventory are maintained in [`../AGENTS.md`](../AGENTS.md).
