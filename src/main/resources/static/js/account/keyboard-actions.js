import { isDraftsPage, isOutboxPage } from 'quicksand/lib/page-context.js'
import {
    hasCheckedEmailSelection,
    getActionTargetEmailId,
    prepareSelectedEmailActionSubmit,
    onChangeEmailSelection,
    updateActionButtons,
    hasSelectedEmailActionTarget
} from 'quicksand/account/email-actions.js'
import {
    getFocusedEmailHeader,
    closeMessagePreview
} from 'quicksand/account/keyboard-focus.js'

function toolbarPresent() {
    return !!document.getElementById('emailcontrols')
        && !isDraftsPage()
        && !isOutboxPage()
}

function resolveTargetEmailId() {
    if (hasCheckedEmailSelection()) {
        return ''
    }
    return getActionTargetEmailId()
}

function prepareSingleMessageAction(emailId) {
    const fallbackSelection = document.getElementById('current-email-action-selection')
    if (!fallbackSelection) {
        return false
    }
    if (hasCheckedEmailSelection()) {
        prepareSelectedEmailActionSubmit()
        return true
    }
    if (!emailId) {
        return false
    }
    fallbackSelection.disabled = false
    fallbackSelection.value = emailId
    return true
}

function clickToolbarAction(buttonName) {
    if (!toolbarPresent()) {
        return false
    }
    const emailId = resolveTargetEmailId()
    if (!emailId && !hasCheckedEmailSelection()) {
        return false
    }
    if (!prepareSingleMessageAction(emailId)) {
        return false
    }
    prepareSelectedEmailActionSubmit()
    const button = document.querySelector(`button[name="${buttonName}"]`)
    if (!button || button.disabled) {
        return false
    }
    button.click()
    return true
}

function focusSearchInput() {
    const input = document.getElementById('searchemailinput')
    if (!input) {
        return false
    }
    input.focus()
    input.select()
    return true
}

function dismissTopLayer() {
    const search = document.getElementById('searchemailinput')
    if (document.activeElement === search) {
        search.blur()
        return true
    }
    const help = document.getElementById('keyboard-shortcuts-help')
    if (help?.open) {
        help.close()
        return true
    }
    const move = document.getElementById('move-emails-dialog')
    if (move?.open) {
        move.close()
        return true
    }
    const composer = document.getElementById('newmail-composer-dialog')
    if (composer?.open) {
        composer.close()
        return true
    }
    if (document.getElementById('messagepreview')?.open) {
        return closeMessagePreview()
    }
    return false
}

async function loadComposerDialog() {
    return import('quicksand/shell/composer-dialog.js')
}

async function composeNew() {
    const button = document.querySelector('button[name="create_new_email"]')
    if (!button) {
        return false
    }
    button.click()
    return true
}

async function composeReplyOrForward(mode) {
    const emailId = resolveTargetEmailId()
    if (!emailId) {
        return false
    }
    const composer = await loadComposerDialog()
    const param = mode === 'reply' ? `replyEmail=${emailId}` : `forwardEmail=${emailId}`
    await composer.createEmailAndShowComposer(param)
    return true
}

function toggleFocusedSelection() {
    const header = getFocusedEmailHeader()
    if (!header) {
        return false
    }
    const checkbox = header.querySelector('.emailselection input[type=checkbox]')
    if (!checkbox) {
        return false
    }
    checkbox.checked = !checkbox.checked
    onChangeEmailSelection()
    return true
}

function openMoveDialog() {
    if (!toolbarPresent()) {
        return false
    }
    const emailId = resolveTargetEmailId()
    if (!emailId && !hasCheckedEmailSelection()) {
        return false
    }
    if (emailId) {
        prepareSingleMessageAction(emailId)
        prepareSelectedEmailActionSubmit()
        updateActionButtons(hasSelectedEmailActionTarget())
    }
    document.getElementById('move-emails-dialog')?.showModal()
    return true
}

function navigateToFolder(specialUse) {
    const link = document.querySelector(`#folderlist a[data-folder-special-use="${specialUse}"]`)
    if (!link) {
        return false
    }
    window.location.href = link.href
    return true
}

function showHelpDialog() {
    const dialog = document.getElementById('keyboard-shortcuts-help')
    if (!dialog) {
        return false
    }
    dialog.showModal()
    return true
}

/** @param {string} action */
export async function executeKeyboardAction(action) {
    switch (action) {
    case 'search.focus':
        return focusSearchInput()
    case 'ui.dismiss':
        return dismissTopLayer()
    case 'selection.toggle':
        return toggleFocusedSelection()
    case 'message.archive':
        return clickToolbarAction('email_action_archive')
    case 'message.delete':
        return clickToolbarAction('email_action_delete')
    case 'message.spam':
        return clickToolbarAction('email_action_mark_spam')
    case 'message.markRead':
        return clickToolbarAction('email_action_mark_read')
    case 'message.markUnread':
        return clickToolbarAction('email_action_mark_unread')
    case 'message.move':
        return openMoveDialog()
    case 'compose.new':
        return composeNew()
    case 'compose.reply':
        return composeReplyOrForward('reply')
    case 'compose.forward':
        return composeReplyOrForward('forward')
    case 'help.show':
        return showHelpDialog()
    case 'go.inbox':
        return navigateToFolder('inbox')
    case 'go.sent':
        return navigateToFolder('sent')
    case 'go.drafts':
        return navigateToFolder('drafts')
    case 'go.archive':
        return navigateToFolder('archive')
    case 'go.spam':
        return navigateToFolder('junk')
    default:
        return false
    }
}
