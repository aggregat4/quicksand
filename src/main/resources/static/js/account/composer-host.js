import { openComposerDialog } from 'quicksand/shell/composer-dialog.js'
import {
    markAllEmailHeadersInactive,
    updateSelectedEmailId
} from 'quicksand/account/message-preview.js'

export function openSelectedDraftComposer(emailId, updateHistory = true) {
    if (updateHistory) {
        updateSelectedEmailId(emailId)
    }
    markAllEmailHeadersInactive()
    document.getElementById(`email${emailId}`)?.classList.add('active')
    openComposerDialog(`/emails/${emailId}/composer`)
}

export function initSelectedDraftComposer() {
    const main = document.querySelector('body.accountpage main')
    if (main?.getAttribute('data-current-folder-is-drafts') !== 'true') {
        return
    }
    const selectedEmailId = main.getAttribute('data-selected-email-id')
    if (!selectedEmailId) {
        return
    }
    openSelectedDraftComposer(selectedEmailId, false)
}
