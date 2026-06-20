/**
 * Default Gmail-style keyboard bindings.
 *
 * Each entry is data-only: the dispatcher maps `action` to behavior in
 * keyboard-actions.js. Stable `id` values are reserved for future user overrides.
 *
 * @typedef {object} KeyboardBinding
 * @property {string} id
 * @property {string} action
 * @property {string|string[]} keys
 * @property {object} [modifiers]
 * @property {string[]} contexts
 * @property {string} label
 * @property {string} category
 */

/** @type {KeyboardBinding[]} */
export const DEFAULT_KEYBOARD_BINDINGS = [
    {
        id: 'list.next',
        action: 'list.next',
        keys: 'j',
        contexts: ['mailbox'],
        label: 'Next message',
        category: 'Navigation'
    },
    {
        id: 'list.prev',
        action: 'list.prev',
        keys: 'k',
        contexts: ['mailbox'],
        label: 'Previous message',
        category: 'Navigation'
    },
    {
        id: 'list.open',
        action: 'list.open',
        keys: 'o',
        contexts: ['mailbox'],
        label: 'Open message',
        category: 'Navigation'
    },
    {
        id: 'list.open.enter',
        action: 'list.open',
        keys: 'enter',
        contexts: ['mailbox'],
        label: 'Open message',
        category: 'Navigation'
    },
    {
        id: 'preview.back',
        action: 'preview.back',
        keys: 'u',
        contexts: ['preview'],
        label: 'Back to list',
        category: 'Navigation'
    },
    {
        id: 'ui.dismiss',
        action: 'ui.dismiss',
        keys: 'escape',
        contexts: ['mailbox', 'preview'],
        label: 'Close dialog / preview',
        category: 'Navigation'
    },
    {
        id: 'search.focus',
        action: 'search.focus',
        keys: '/',
        contexts: ['mailbox', 'preview'],
        label: 'Search mail',
        category: 'Navigation'
    },
    {
        id: 'selection.toggle',
        action: 'selection.toggle',
        keys: 'x',
        contexts: ['mailbox'],
        label: 'Select message',
        category: 'Selection'
    },
    {
        id: 'message.archive',
        action: 'message.archive',
        keys: 'e',
        contexts: ['mailbox', 'preview'],
        label: 'Archive',
        category: 'Actions'
    },
    {
        id: 'message.delete',
        action: 'message.delete',
        keys: '#',
        contexts: ['mailbox', 'preview'],
        label: 'Delete',
        category: 'Actions'
    },
    {
        id: 'message.spam',
        action: 'message.spam',
        keys: '!',
        contexts: ['mailbox', 'preview'],
        label: 'Report spam',
        category: 'Actions'
    },
    {
        id: 'message.markRead',
        action: 'message.markRead',
        keys: 'i',
        modifiers: { shift: true },
        contexts: ['mailbox', 'preview'],
        label: 'Mark as read',
        category: 'Actions'
    },
    {
        id: 'message.markUnread',
        action: 'message.markUnread',
        keys: 'u',
        modifiers: { shift: true },
        contexts: ['mailbox', 'preview'],
        label: 'Mark as unread',
        category: 'Actions'
    },
    {
        id: 'message.move',
        action: 'message.move',
        keys: 'v',
        contexts: ['mailbox', 'preview'],
        label: 'Move to folder',
        category: 'Actions'
    },
    {
        id: 'compose.new',
        action: 'compose.new',
        keys: 'c',
        contexts: ['mailbox', 'preview'],
        label: 'Compose',
        category: 'Compose'
    },
    {
        id: 'compose.reply',
        action: 'compose.reply',
        keys: 'r',
        contexts: ['mailbox', 'preview'],
        label: 'Reply',
        category: 'Compose'
    },
    {
        id: 'compose.forward',
        action: 'compose.forward',
        keys: 'f',
        contexts: ['mailbox', 'preview'],
        label: 'Forward',
        category: 'Compose'
    },
    {
        id: 'help.show',
        action: 'help.show',
        keys: '?',
        contexts: ['mailbox', 'preview'],
        label: 'Show shortcuts',
        category: 'Help'
    },
    {
        id: 'go.inbox',
        action: 'go.inbox',
        keys: ['g', 'i'],
        contexts: ['mailbox', 'preview'],
        label: 'Go to Inbox',
        category: 'Go to'
    },
    {
        id: 'go.sent',
        action: 'go.sent',
        keys: ['g', 't'],
        contexts: ['mailbox', 'preview'],
        label: 'Go to Sent',
        category: 'Go to'
    },
    {
        id: 'go.drafts',
        action: 'go.drafts',
        keys: ['g', 'd'],
        contexts: ['mailbox', 'preview'],
        label: 'Go to Drafts',
        category: 'Go to'
    },
    {
        id: 'go.archive',
        action: 'go.archive',
        keys: ['g', 'a'],
        contexts: ['mailbox', 'preview'],
        label: 'Go to Archive',
        category: 'Go to'
    },
    {
        id: 'go.spam',
        action: 'go.spam',
        keys: ['g', 'b'],
        contexts: ['mailbox', 'preview'],
        label: 'Go to Spam',
        category: 'Go to'
    }
]
