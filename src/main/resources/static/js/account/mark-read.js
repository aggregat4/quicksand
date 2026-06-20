import { isDraftsPage, isOutboxPage } from 'quicksand/lib/page-context.js'

const markReadInFlight = new Set()
const markReadCompleted = new Set()
let markReadAbortController = null

export function abortPendingMarkRead() {
    if (markReadAbortController) {
        markReadAbortController.abort()
        markReadAbortController = null
    }
}

export function markEmailHeaderReadLocally(header) {
    if (!header || header.classList.contains('read')) {
        return
    }
    header.classList.add('read')
}

export function markReadOnServer(emailId, { force = false } = {}) {
    if (!emailId) {
        return
    }
    if (!force) {
        const header = document.getElementById(`email${emailId}`)
        if (header?.classList.contains('read') || markReadCompleted.has(emailId)) {
            return
        }
    }
    if (markReadInFlight.has(emailId)) {
        return
    }
    markReadAbortController?.abort()
    const abortController = new AbortController()
    markReadAbortController = abortController
    markReadInFlight.add(emailId)
    fetch(`/emails/${emailId}/read`, {
        method: 'POST',
        credentials: 'same-origin',
        signal: abortController.signal
    })
        .then((response) => {
            if (response.ok) {
                markReadCompleted.add(emailId)
            }
        })
        .catch(() => {})
        .finally(() => {
            markReadInFlight.delete(emailId)
            if (markReadAbortController === abortController) {
                markReadAbortController = null
            }
        })
}

export function initOpenMessageReadState() {
    if (isDraftsPage() || isOutboxPage()) {
        return
    }
    const accountMain = document.querySelector('body.accountpage main[data-selected-email-id]')
    if (!accountMain) {
        return
    }
    const url = new URL(window.location.href)
    if (url.searchParams.get('markedUnread') === '1') {
        url.searchParams.delete('markedUnread')
        history.replaceState(null, '', url.toString())
        return
    }
    const preview = document.getElementById('messagepreview')
    if (!preview?.open) {
        return
    }
    const emailId = accountMain.getAttribute('data-selected-email-id')
    if (emailId) {
        markReadOnServer(emailId)
    }
}
