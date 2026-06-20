export function isDraftsPage() {
    return document.querySelector('body.accountpage main')?.getAttribute('data-current-folder-is-drafts') === 'true'
}

export function isOutboxPage() {
    return document.querySelector('body.accountpage main')?.getAttribute('data-current-folder-is-outbox') === 'true'
}

export function currentFolderId() {
    return document.querySelector('main')?.dataset.currentNamedFolderId || ''
}
