import { getEmailIdFromNode } from 'quicksand/lib/email-id.js'
import { isDraftsPage } from 'quicksand/lib/page-context.js'
import { onEmailHeaderClick, onCloseMessagePreview } from 'quicksand/account/message-preview.js'
import { openSelectedDraftComposer } from 'quicksand/account/composer-host.js'
import {
    hasSelectedEmailActionTarget,
    updateActionButtons
} from 'quicksand/account/email-actions.js'

function listBody() {
    return document.getElementById('messagelist-body')
}

export function getEmailHeaders() {
    return Array.from(listBody()?.querySelectorAll('a.emailheader') ?? [])
}

function headerIndex(header) {
    const headers = getEmailHeaders()
    return headers.indexOf(header)
}

export function getFocusedEmailHeader() {
    const active = document.activeElement
    if (active?.matches?.('a.emailheader')) {
        return active
    }
    return null
}

export function getFocusedEmailId() {
    const header = getFocusedEmailHeader()
    return header ? getEmailIdFromNode(header) : ''
}

function getActiveEmailHeader() {
    return document.querySelector('#messagelist a.emailheader.active')
}

function syncTabIndex(header) {
    getEmailHeaders().forEach((node) => {
        node.tabIndex = node === header ? 0 : -1
    })
}

export function focusEmailHeader(header) {
    if (!header) {
        return false
    }
    syncTabIndex(header)
    header.focus({ preventScroll: true })
    header.scrollIntoView({ block: 'nearest' })
    updateActionButtons(hasSelectedEmailActionTarget())
    return true
}

export function focusEmailById(emailId) {
    if (!emailId) {
        return false
    }
    const header = getEmailHeaders().find((node) => getEmailIdFromNode(node) === emailId)
    return focusEmailHeader(header)
}

export function moveListFocus(delta) {
    const headers = getEmailHeaders()
    if (headers.length === 0) {
        return false
    }
    const current = getFocusedEmailHeader() ?? getActiveEmailHeader()
    let index = headerIndex(current)
    if (index < 0) {
        index = delta > 0 ? -1 : headers.length
    }
    const nextIndex = Math.max(0, Math.min(headers.length - 1, index + delta))
    return focusEmailHeader(headers[nextIndex])
}

export function openFocusedMessage() {
    const header = getFocusedEmailHeader() ?? getActiveEmailHeader()
    if (!header) {
        return false
    }
    if (isDraftsPage()) {
        openSelectedDraftComposer(getEmailIdFromNode(header))
        return true
    }
    onEmailHeaderClick({ preventDefault() {} }, header)
    return true
}

export function closeMessagePreview() {
    const preview = document.getElementById('messagepreview')
    if (!preview?.open) {
        return false
    }
    preview.close()
    onCloseMessagePreview()
    const selectedId = document.querySelector('main')?.dataset.selectedEmailId
    if (selectedId) {
        focusEmailById(selectedId)
    } else {
        focusEmailHeader(getActiveEmailHeader())
    }
    return true
}

export function initKeyboardFocus() {
    const headers = getEmailHeaders()
    headers.forEach((header) => {
        header.tabIndex = -1
    })
    const selectedId = document.querySelector('main')?.dataset.selectedEmailId
    if (selectedId && focusEmailById(selectedId)) {
        return
    }
    if (getActiveEmailHeader()) {
        syncTabIndex(getActiveEmailHeader())
        updateActionButtons(hasSelectedEmailActionTarget())
        return
    }
    if (headers.length > 0) {
        syncTabIndex(headers[0])
    }
}

export { getEmailIdFromNode }
