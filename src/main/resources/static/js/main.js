// if we are already loaded then DOMContentLoaded will not fire again, just init
if (document.readyState !== 'loading') {
    init()
} else {
    document.addEventListener('DOMContentLoaded', init)
}

function init() {
    const toggleFolderListButton = document.getElementById('toggle-folderlist')
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
}
