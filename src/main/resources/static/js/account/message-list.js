import { isDraftsPage } from 'quicksand/lib/page-context.js'
import { getEmailIdFromNode } from 'quicksand/lib/email-id.js'
import {
    onChangeEmailSelection,
    onClickSelectAllEmails
} from 'quicksand/account/email-actions.js'
import { onEmailHeaderClick } from 'quicksand/account/message-preview.js'
import { openSelectedDraftComposer } from 'quicksand/account/composer-host.js'

export function initMessageList() {
    document.getElementById('select-all-mail-checkbox')
        ?.addEventListener('click', onClickSelectAllEmails)
    document.getElementById('select-all-mail-checkbox')
        ?.addEventListener('keydown', (event) => {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                onClickSelectAllEmails()
            }
        })
    document.querySelector('button[name="email_action_open_move_dialog"]')
        ?.addEventListener('click', () => {
            document.getElementById('move-emails-dialog')?.showModal()
        })
    document.getElementById('messagelist')?.addEventListener('click', (event) => {
        if (event.target.closest('.emailactions') || event.target.closest('.emailselection')) {
            return
        }
        const header = event.target.closest('a.emailheader')
        if (!header) {
            return
        }
        if (isDraftsPage()) {
            onDraftHeaderClick(event, header)
        } else {
            onEmailHeaderClick(event, header)
        }
    }, true)
    document.getElementById('messagelist')?.addEventListener('change', (event) => {
        if (event.target.matches('.emailselection input[type="checkbox"]')) {
            onChangeEmailSelection()
        }
    })
}

function onDraftHeaderClick(event, header = event.currentTarget) {
    event.preventDefault()
    openSelectedDraftComposer(getEmailIdFromNode(header))
}
