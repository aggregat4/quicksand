// if we are already loaded then DOMContentLoaded will not fire again, just init
if (document.readyState !== 'loading') {
    init()
} else {
    document.addEventListener('DOMContentLoaded', init)
}

function init() {
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
    // open submenus on hover and close automatically after a timeout (instead of immediately disappearing with css hover)
    const menuItems = document.querySelectorAll('li.has-submenu')
    let menuTimer = null
    Array.prototype.forEach.call(menuItems, function(el){
        el.addEventListener('mouseover', function(){
            this.classList.add('open')
            clearTimeout(menuTimer)
        });
        el.addEventListener('mouseout', function(){
            menuTimer = setTimeout(function(){
                document.querySelector('.has-submenu.open').className = 'has-submenu'
            }, 1000)
        });
        // activate the submenus on activation (click, keyboard activation, etc) for non sighted users
        el.querySelector('a').addEventListener('click',  function(event){
            if (!this.parentNode.classList.contains('open')) {
                this.parentNode.classList.add('open')
                this.setAttribute('aria-expanded', 'true')
            } else {
                this.parentNode.classList.remove('open')
                this.setAttribute('aria-expanded', 'false')
            }
            event.preventDefault()
            return false
        });
    });
    initSelectedDraftComposer()
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
    updateActionButtons(anyMailsSelected)
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
    updateActionButtons(!anySelectionPresent)
}

function changeSelectionOfEmails(selectAll) {
    document.querySelectorAll('.emailselection input[type=checkbox]')
        .forEach(node => node.checked = !!selectAll)
}

function onEmailHeaderClick(event) {
    document.getElementById('messagepreview').show()
    markAllEmailHeadersInactive()
    event.currentTarget.classList.add('active')
    // modify URL to reflect selected email
    const emailIdAttribute = event.currentTarget.getAttribute('id')
    const prefixLength = 'email'.length
    const emailId = emailIdAttribute.substring(prefixLength)
    const url = new URL(window.location.href)
    url.searchParams.delete('selectedEmailId')
    url.searchParams.append('selectedEmailId', emailId)
    history.pushState(null, '', url.toString())
}

function markAllEmailHeadersInactive() {
    document.querySelectorAll('#messagelist a.active')
        .forEach(node => node.classList.remove('active'))
}

function onCloseMessagePreview() {
    markAllEmailHeadersInactive()
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
    }
});

async function createEmailAndShowComposer(urlParams) {
    const queryString = urlParams ? `${urlParams}&redirect=false` : 'redirect=false'
    const response = await fetch(
        `/accounts/${window.quicksand.currentAccountId}/emails?${queryString}`,
        {method: 'POST'})
    openComposerDialog(await response.text())
}

function onDraftHeaderClick(event) {
    openSelectedDraftComposer(getEmailIdFromNode(event.currentTarget))
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
