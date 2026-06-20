import {
    createEmailAndShowComposer,
    onCloseEmailComposerDialog
} from 'quicksand/account/composer-host.js'

export function initHeader() {
    document.querySelector('button[name="create_new_email"]')
        ?.addEventListener('click', () => {
            void createEmailAndShowComposer()
        })
    document.querySelector('button[name="closeMessageComposer"]')
        ?.addEventListener('click', (event) => {
            void onCloseEmailComposerDialog(event)
        })

    const toggleFolderListButton = document.getElementById('toggle-folderlist-button')
    if (toggleFolderListButton) {
        toggleFolderListButton.addEventListener('click', () => {
            const folderList = document.getElementById('folderlist')
            if (!folderList) {
                return
            }
            const pressed = toggleFolderListButton.getAttribute('aria-pressed')
            if (pressed === 'true') {
                folderList.style.display = 'none'
                toggleFolderListButton.setAttribute('aria-pressed', 'false')
            } else {
                folderList.style.display = 'block'
                toggleFolderListButton.setAttribute('aria-pressed', 'true')
            }
        })
    }
}
