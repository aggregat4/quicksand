import { getEmailIdFromNode } from 'quicksand/lib/email-id.js'
import { markEmailHeaderReadLocally, markReadOnServer } from 'quicksand/account/mark-read.js'
import {
    hasSelectedEmailActionTarget,
    updateActionButtons
} from 'quicksand/account/email-actions.js'

export function markAllEmailHeadersInactive() {
    document.querySelectorAll('#messagelist a.active')
        .forEach(node => node.classList.remove('active'))
}

export function updateSelectedEmailId(emailId) {
    const url = new URL(window.location.href)
    url.searchParams.delete('selectedEmailId')
    url.searchParams.append('selectedEmailId', emailId)
    history.pushState(null, '', url.toString())
}

export function onEmailHeaderClick(event, header = event.currentTarget) {
    event.preventDefault()
    const emailId = getEmailIdFromNode(header)
    document.getElementById('messagepreview').show()
    markAllEmailHeadersInactive()
    header.classList.add('active')
    markEmailHeaderReadLocally(header)
    markReadOnServer(emailId, { force: true })
    updateActionButtons(hasSelectedEmailActionTarget())
    updateSelectedEmailId(emailId)

    const viewer = document.querySelector('iframe[name="emailviewer"]')
    if (viewer) {
        viewer.src = header.href
    }
}

export function onCloseMessagePreview() {
    markAllEmailHeadersInactive()
    updateActionButtons(hasSelectedEmailActionTarget())
    const url = new URL(window.location.href)
    url.searchParams.delete('selectedEmailId')
    history.pushState(null, '', url.toString())
}

export function initMessagePreview() {
    document.querySelectorAll('button[name="closeMessageViewer"]').forEach((button) => {
        button.addEventListener('click', onCloseMessagePreview)
    })
}

export { getEmailIdFromNode }
