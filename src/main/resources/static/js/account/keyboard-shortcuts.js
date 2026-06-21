import { isDraftsPage } from 'quicksand/lib/page-context.js'
import { matchBinding, formatBindingKeys } from 'quicksand/lib/key-sequence.js'
import { DEFAULT_KEYBOARD_BINDINGS } from 'quicksand/account/keyboard-bindings.js'
import {
    initKeyboardFocus,
    moveListFocus,
    openFocusedMessage,
    closeMessagePreview
} from 'quicksand/account/keyboard-focus.js'
import { executeKeyboardAction } from 'quicksand/account/keyboard-actions.js'

const CHORD_TIMEOUT_MS = 1000

function getActiveContexts() {
    const contexts = new Set()
    if (document.getElementById('messagelist')) {
        contexts.add('mailbox')
        if (isDraftsPage()) {
            contexts.add('drafts')
        }
    }
    if (document.getElementById('messagepreview')?.open) {
        contexts.add('preview')
    }
    return contexts
}

function shouldIgnoreEvent(event) {
    if (event.defaultPrevented || event.isComposing) {
        return true
    }
    const dismissing = event.key === 'Escape'
    const blockingDialogOpen = [
        'newmail-composer-dialog',
        'move-emails-dialog',
        'keyboard-shortcuts-help'
    ].some(id => document.getElementById(id)?.open)
    if (blockingDialogOpen && !dismissing) {
        return true
    }
    const target = event.target
    if (!(target instanceof Element)) {
        return false
    }
    if (target.closest('input, textarea, select, [contenteditable="true"]')) {
        if (dismissing) {
            return false
        }
        return true
    }
    return false
}

async function runListAction(action) {
    switch (action) {
    case 'list.next':
        return moveListFocus(1)
    case 'list.prev':
        return moveListFocus(-1)
    case 'list.open':
        return openFocusedMessage()
    case 'preview.back':
        return closeMessagePreview()
    default:
        return executeKeyboardAction(action)
    }
}

function renderHelpContent(bindings) {
    const container = document.getElementById('keyboard-shortcuts-help-content')
    if (!container) {
        return
    }
    const categories = new Map()
    bindings.forEach((binding) => {
        if (!binding.label) {
            return
        }
        const rows = categories.get(binding.category) ?? []
        rows.push(binding)
        categories.set(binding.category, rows)
    })

    container.replaceChildren()
    categories.forEach((rows, category) => {
        const section = document.createElement('section')
        section.className = 'keyboard-shortcuts-category'

        const heading = document.createElement('h2')
        heading.textContent = category
        section.appendChild(heading)

        const list = document.createElement('dl')
        list.className = 'keyboard-shortcuts-list'
        rows.forEach((binding) => {
            const dt = document.createElement('dt')
            const label = formatBindingKeys(binding.keys)
            dt.textContent = binding.modifiers?.shift ? `Shift+${label}` : label
            const dd = document.createElement('dd')
            dd.textContent = binding.label
            list.append(dt, dd)
        })
        section.appendChild(list)
        container.appendChild(section)
    })
}

export function initKeyboardShortcuts(bindings = DEFAULT_KEYBOARD_BINDINGS) {
    if (!document.getElementById('messagelist')) {
        return
    }

    initKeyboardFocus()
    renderHelpContent(bindings)
    document.body.dataset.keyboardShortcuts = 'ready'

    let pendingKeys = []
    let chordTimer = null

    function resetChord() {
        pendingKeys = []
        clearTimeout(chordTimer)
        chordTimer = null
    }

    function armChordTimeout() {
        clearTimeout(chordTimer)
        chordTimer = setTimeout(resetChord, CHORD_TIMEOUT_MS)
    }

    document.addEventListener('keydown', (event) => {
        if (shouldIgnoreEvent(event)) {
            return
        }

        const contexts = getActiveContexts()
        const { binding, pendingKeys: nextPending } = matchBinding(
            event,
            pendingKeys,
            bindings,
            contexts
        )

        if (binding) {
            event.preventDefault()
            resetChord()
            void runListAction(binding.action)
            return
        }

        if (nextPending.length > 0) {
            event.preventDefault()
            pendingKeys = nextPending
            armChordTimeout()
            return
        }

        if (pendingKeys.length > 0) {
            resetChord()
        }
    })
}
