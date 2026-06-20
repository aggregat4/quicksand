import { abortPendingMarkRead } from 'quicksand/account/mark-read.js'

const MESSAGE_LIST_SCROLL_STORAGE_PREFIX = 'quicksand.messagelistScroll:'

function messageListScrollKey() {
    const main = document.querySelector('body.accountpage main')
    const folderId = main?.getAttribute('data-current-named-folder-id') ?? ''
    const url = new URL(window.location.href)
    const stableParams = new URLSearchParams()
    for (const param of [
        'pageDirection',
        'sortOrder',
        'offsetReceivedTimestamp',
        'offsetMessageId',
        'pagePosition',
        'query'
    ]) {
        const value = url.searchParams.get(param)
        if (value != null && value !== '') {
            stableParams.set(param, value)
        }
    }
    return `${MESSAGE_LIST_SCROLL_STORAGE_PREFIX}${folderId}:${stableParams.toString()}`
}

export function saveMessageListScroll() {
    const list = document.getElementById('messagelist')
    if (!list) {
        return
    }
    const listTop = list.getBoundingClientRect().top
    let anchorId = ''
    let anchorOffset = 0
    for (const header of list.querySelectorAll('.emailheader')) {
        const rect = header.getBoundingClientRect()
        if (rect.bottom > listTop + 1) {
            anchorId = header.dataset.messageId || ''
            anchorOffset = rect.top - listTop
            break
        }
    }
    sessionStorage.setItem(
        messageListScrollKey(),
        JSON.stringify({ scrollTop: list.scrollTop, anchorId, anchorOffset })
    )
}

function restoreMessageListScroll() {
    const list = document.getElementById('messagelist')
    if (!list) {
        return
    }
    const key = messageListScrollKey()
    const saved = sessionStorage.getItem(key)
    if (saved == null) {
        return
    }
    sessionStorage.removeItem(key)
    let payload
    try {
        payload = JSON.parse(saved)
    } catch (error) {
        payload = { scrollTop: Number.parseInt(saved, 10) }
    }
    if (Number.isFinite(payload.scrollTop) && payload.scrollTop >= 0) {
        list.scrollTop = payload.scrollTop
    }
    if (payload.anchorId) {
        const row = document.getElementById(`email${payload.anchorId}`)
        if (row && Number.isFinite(payload.anchorOffset)) {
            const delta = row.getBoundingClientRect().top
                - list.getBoundingClientRect().top
                - payload.anchorOffset
            list.scrollTop += delta
        }
    }
}

function isEmailSelectionForm(form) {
    if (!(form instanceof HTMLFormElement)) {
        return false
    }
    if (form.id === 'selected-email-actions') {
        return true
    }
    const action = form.getAttribute('action') || ''
    return action === '/emails/selection' || action.endsWith('/emails/selection')
}

export function initMessageListScrollPersistence() {
    if (!document.getElementById('messagelist')) {
        return
    }
    if ('scrollRestoration' in history) {
        history.scrollRestoration = 'manual'
    }
    restoreMessageListScroll()
    document.addEventListener('submit', (event) => {
        if (isEmailSelectionForm(event.target)) {
            abortPendingMarkRead()
            saveMessageListScroll()
        }
    }, true)
    document.addEventListener('mousedown', (event) => {
        const button = event.target.closest('button[name^="email_action_"]')
        if (!button?.form || !isEmailSelectionForm(button.form)) {
            return
        }
        abortPendingMarkRead()
        saveMessageListScroll()
    }, true)
}
