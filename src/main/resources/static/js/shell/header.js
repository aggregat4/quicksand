function loadComposerDialog() {
    return import('quicksand/shell/composer-dialog.js')
}

export function initHeader() {
    document.querySelector('button[name="create_new_email"]')
        ?.addEventListener('click', () => {
            void loadComposerDialog().then((module) => module.createEmailAndShowComposer())
        })
    document.querySelector('button[name="closeMessageComposer"]')
        ?.addEventListener('click', (event) => {
            void loadComposerDialog().then((module) => module.onCloseEmailComposerDialog(event))
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
