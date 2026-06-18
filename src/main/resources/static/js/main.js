function init() {
    initQuicksandRuntime()
    initMessageListScrollPersistence()
    initHeaderActions()
    initAccountPage()
    const toggleFolderListButton = document.getElementById('toggle-folderlist-button')
    if (toggleFolderListButton) {
        toggleFolderListButton.addEventListener('click', () => {
            const folderList = document.getElementById('folderlist')
            if (folderList) {
                const pressed = toggleFolderListButton.getAttribute('aria-pressed')
                if (pressed === 'true') {
                    folderList.style.display = 'none'
                    toggleFolderListButton.setAttribute('aria-pressed', false)
                } else {
                    folderList.style.display = 'block'
                    toggleFolderListButton.setAttribute('aria-pressed', true)
                }
            }
        })
    }
    initSelectedDraftComposer()
    initSelectedEmailActions()
    initOpenMessageReadState()
    initBackForwardCacheRecovery()
}

function initBackForwardCacheRecovery() {
    window.addEventListener('pageshow', (event) => {
        if (event.persisted) {
            location.reload()
        }
    })
}

function initQuicksandRuntime() {
    window.quicksand = window.quicksand || {}
    const accountId = document.body.dataset.accountId
    if (accountId) {
        window.quicksand.currentAccountId = Number.parseInt(accountId, 10)
    }
}

function initHeaderActions() {
    document.querySelector('button[name="create_new_email"]')
        ?.addEventListener('click', () => {
            void createEmailAndShowComposer()
        })
    document.querySelector('button[name="closeMessageComposer"]')
        ?.addEventListener('click', (event) => {
            void onCloseEmailComposerDialog(event)
        })
}

function initAccountPage() {
    if (!document.body.classList.contains('accountpage')) {
        return
    }
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
    document.querySelectorAll('button[name="closeMessageViewer"]').forEach((button) => {
        button.addEventListener('click', onCloseMessagePreview)
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

function saveMessageListScroll() {
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

function abortPendingMarkRead() {
    if (markReadAbortController) {
        markReadAbortController.abort()
        markReadAbortController = null
    }
}

const markReadInFlight = new Set()
const markReadCompleted = new Set()
let markReadAbortController = null

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

function initMessageListScrollPersistence() {
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

/*
  Called when the selection state of a mail changes. It checks if the selection is empty and update the state of the
  bulk action buttons accordingly.
 */
function onChangeEmailSelection() {
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

function updateActionButtons(anyMailsSelected) {
    document.querySelectorAll('#emailcontrols .emailaction')
        .forEach((button) => button.disabled = !anyMailsSelected)
}

function initSelectedEmailActions() {
    const actionForm = document.getElementById('selected-email-actions')
    if (!actionForm) {
        return
    }
    actionForm.addEventListener('submit', prepareSelectedEmailActionSubmit)
    updateActionButtons(hasSelectedEmailActionTarget(hasCheckedEmailSelection()))
}

function prepareSelectedEmailActionSubmit() {
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

function hasSelectedEmailActionTarget(anyMailsSelected = hasCheckedEmailSelection()) {
    return anyMailsSelected || !!getActiveEmailId()
}

function hasCheckedEmailSelection() {
    return Array.from(document.querySelectorAll('.emailselection input[type=checkbox]'))
        .some(node => node.checked)
}

function getActiveEmailId() {
    const activeEmail = document.querySelector('#messagelist a.emailheader.active')
    return activeEmail ? getEmailIdFromNode(activeEmail) : ''
}

function onClickSelectAllEmails() {
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
        .forEach(node => node.checked = !!selectAll)
}

function onEmailHeaderClick(event, header = event.currentTarget) {
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

function markAllEmailHeadersInactive() {
    document.querySelectorAll('#messagelist a.active')
        .forEach(node => node.classList.remove('active'))
}

function markEmailHeaderReadLocally(header) {
    if (!header || header.classList.contains('read')) {
        return
    }
    header.classList.add('read')
}

function onCloseMessagePreview() {
    markAllEmailHeadersInactive()
    updateActionButtons(hasSelectedEmailActionTarget())
    const url = new URL(window.location.href)
    url.searchParams.delete('selectedEmailId')
    history.pushState(null, '', url.toString())
}

function onCloseEmailComposerDialog(event) {
    event?.preventDefault()
    void closeEmailComposerDialog()
    return false
}

/*
    Installs a postMessage event listener that listens for messages from iframes to act on.
 */
window.addEventListener("message", async function (event) {
    if (event.data.type === "email-queued") {
        // reduce the size of the composer window to be a small bar
        // set a timeout for closing that window automatically
        const composer = document.getElementById('newmail-composer-dialog')
        if (composer) {
            composer.classList.add('minimized')
            setTimeout(() => {
                composer.close()
                composer.classList.remove('minimized')
                if (isDraftsPage()) {
                    window.location.href = window.location.pathname
                }
            }, 7000);
        }
    } else if (event.data.type === "reply-to-email") {
        await createEmailAndShowComposer(`replyEmail=${event.data.emailId}`);
    } else if (event.data.type === "forward-email") {
        await createEmailAndShowComposer(`forwardEmail=${event.data.emailId}`);
    } else if (event.data.type === "close-email-composer") {
        await closeEmailComposerDialog();
    }
});

async function createEmailAndShowComposer(urlParams) {
    const queryString = urlParams ? `${urlParams}&redirect=false` : 'redirect=false'
    const response = await fetch(
        `/accounts/${window.quicksand.currentAccountId}/emails?${queryString}`,
        {method: 'POST'})
    openComposerDialog(await response.text())
}

function onDraftHeaderClick(event, header = event.currentTarget) {
    event.preventDefault()
    openSelectedDraftComposer(getEmailIdFromNode(header))
}

function initOpenMessageReadState() {
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

function markReadOnServer(emailId, { force = false } = {}) {
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

function isOutboxPage() {
    return document.querySelector('body.accountpage main')?.getAttribute('data-current-folder-is-outbox') === 'true'
}

function initSelectedDraftComposer() {
    if (!isDraftsPage()) {
        return
    }
    const accountMain = document.querySelector('body.accountpage main')
    const selectedEmailId = accountMain?.getAttribute('data-selected-email-id')
    if (!selectedEmailId) {
        return
    }
    openSelectedDraftComposer(selectedEmailId, false)
}

function isDraftsPage() {
    return document.querySelector('body.accountpage main')?.getAttribute('data-current-folder-is-drafts') === 'true'
}

async function closeEmailComposerDialog() {
    const composer = document.getElementById('newmail-composer-dialog')
    const composerFrame = document.getElementById('newmail-composer-frame')
    composer.classList.remove('minimized')
    await requestComposerDraftSave()
    composer.close()
    composerFrame.src = 'about:blank'
    if (isDraftsPage()) {
        markAllEmailHeadersInactive()
        const url = new URL(window.location.href)
        url.searchParams.delete('selectedEmailId')
        history.pushState(null, '', url.toString())
    }
}

function openComposerDialog(src) {
    const composerFrame = document.getElementById('newmail-composer-frame')
    composerFrame.src = 'about:blank'
    composerFrame.src = src
    document.getElementById('newmail-composer-dialog').show()
}

function openSelectedDraftComposer(emailId, updateHistory = true) {
    if (updateHistory) {
        updateSelectedEmailId(emailId)
    }
    markAllEmailHeadersInactive()
    document.getElementById(`email${emailId}`)?.classList.add('active')
    openComposerDialog(`/emails/${emailId}/composer`)
}

function getEmailIdFromNode(node) {
    const emailIdAttribute = node.getAttribute('id')
    return emailIdAttribute.substring('email'.length)
}

function updateSelectedEmailId(emailId) {
    const url = new URL(window.location.href)
    url.searchParams.delete('selectedEmailId')
    url.searchParams.append('selectedEmailId', emailId)
    history.pushState(null, '', url.toString())
}

function requestComposerDraftSave() {
    const composerFrame = document.getElementById('newmail-composer-frame')
    if (!composerFrame?.contentWindow) {
        return Promise.resolve()
    }
    const composerPath = composerFrame.contentWindow.location?.pathname ?? ''
    if (!composerPath.endsWith('/composer')) {
        return Promise.resolve()
    }
    return new Promise(resolve => {
        let settled = false
        const timeoutId = setTimeout(() => {
            finish()
        }, 1500)

        function onMessage(messageEvent) {
            if (messageEvent.source !== composerFrame.contentWindow) {
                return
            }
            if (messageEvent.data?.type !== 'draft-saved') {
                return
            }
            finish()
        }

        function finish() {
            if (settled) {
                return
            }
            settled = true
            clearTimeout(timeoutId)
            window.removeEventListener('message', onMessage)
            resolve()
        }

        window.addEventListener('message', onMessage)
        composerFrame.contentWindow.postMessage({ type: 'save-draft' }, '*')
    })
}

if (document.readyState !== 'loading') {
    init()
} else {
    document.addEventListener('DOMContentLoaded', init)
}
