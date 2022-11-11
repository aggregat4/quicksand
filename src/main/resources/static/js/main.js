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
    Array.prototype.forEach.call(menuItems, function(el, i){
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
    document.querySelectorAll('#selected-email-controls > button')
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
        .forEach(node => {
            node.checked = !!selectAll
        })
}

function onEmailHeaderClick(event) {
    // make the preview window visible
    const messagePreviewDialog = document.getElementById('messagepreview')
    if (!messagePreviewDialog.open) {
        messagePreviewDialog.show()
    }
    if (! event.currentTarget.classList.contains('active')) {
        markAllEmailHeadersInactive()
        event.currentTarget.classList.add('active')
    }
}

function markAllEmailHeadersInactive() {
    const emailHeaderNodelist = document.querySelectorAll('#messagelist a')
    emailHeaderNodelist.forEach(node => {
        if (node.classList.contains('active')) {
            node.classList.remove('active')
        }
    })
}

function onCloseMessagePreview() {
    markAllEmailHeadersInactive()
}
