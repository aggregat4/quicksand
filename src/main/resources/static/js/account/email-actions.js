import { getEmailIdFromNode } from 'quicksand/lib/email-id.js'
import { abortPendingMarkRead } from 'quicksand/account/mark-read.js'
import { saveMessageListScroll } from 'quicksand/account/scroll-persist.js'

export function hasCheckedEmailSelection() {
    return Array.from(document.querySelectorAll('.emailselection input[type=checkbox]'))
        .some(node => node.checked)
}

export function getActiveEmailId() {
    const activeEmail = document.querySelector('#messagelist a.emailheader.active')
    return activeEmail ? getEmailIdFromNode(activeEmail) : ''
}

export function hasSelectedEmailActionTarget(anyMailsSelected = hasCheckedEmailSelection()) {
    return anyMailsSelected || !!getActiveEmailId()
}

export function updateActionButtons(anyMailsSelected) {
    document.querySelectorAll('#emailcontrols .emailaction')
        .forEach((button) => { button.disabled = !anyMailsSelected })
}

export function initSelectedEmailActions() {
    const actionForm = document.getElementById('selected-email-actions')
    if (!actionForm) {
        return
    }
    actionForm.addEventListener('submit', prepareSelectedEmailActionSubmit)
    updateActionButtons(hasSelectedEmailActionTarget(hasCheckedEmailSelection()))
}

export function prepareSelectedEmailActionSubmit() {
    abortPendingMarkRead()
    saveMessageListScroll()
    const fallbackSelection = document.getElementById('current-email-action-selection')
    if (!fallbackSelection) {
        return
    }
    const checkedSelectionPresent = hasCheckedEmailSelection()
    const selectedEmailId = getActiveEmailId()
    fallbackSelection.disabled = checkedSelectionPresent || !selectedEmailId
    fallbackSelection.value = checkedSelectionPresent || !selectedEmailId ? '' : selectedEmailId
}

export function onChangeEmailSelection() {
    let allMailsSelected = true
    let anyMailsSelected = false
    document.querySelectorAll('.emailselection input[type=checkbox]')
        .forEach(node => {
            anyMailsSelected ||= node.checked
            allMailsSelected &&= node.checked
        })
    updateActionButtons(hasSelectedEmailActionTarget(anyMailsSelected))
    const allEmailSelectionCheckBox = document.getElementById('select-all-mail-checkbox')
    if (allMailsSelected) {
        allEmailSelectionCheckBox.setAttribute('aria-checked', 'true')
    } else if (anyMailsSelected) {
        allEmailSelectionCheckBox.setAttribute('aria-checked', 'mixed')
    } else {
        allEmailSelectionCheckBox.setAttribute('aria-checked', 'false')
    }
}

export function onClickSelectAllEmails() {
    const allEmailSelectionCheckBox = document.getElementById('select-all-mail-checkbox')
    const ariaCheckedState = allEmailSelectionCheckBox.getAttribute('aria-checked')
    const anySelectionPresent = (ariaCheckedState === 'true' || ariaCheckedState === 'mixed')
    if (anySelectionPresent) {
        allEmailSelectionCheckBox.setAttribute('aria-checked', 'false')
        changeSelectionOfEmails(false)
    } else {
        allEmailSelectionCheckBox.setAttribute('aria-checked', 'true')
        changeSelectionOfEmails(true)
    }
    updateActionButtons(hasSelectedEmailActionTarget(!anySelectionPresent))
}

function changeSelectionOfEmails(selectAll) {
    document.querySelectorAll('.emailselection input[type=checkbox]')
        .forEach(node => { node.checked = !!selectAll })
}
