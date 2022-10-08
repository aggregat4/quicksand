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
        el.addEventListener("mouseover", function(event){
            this.classList.add("open")
            clearTimeout(menuTimer)
        });
        el.addEventListener("mouseout", function(event){
            menuTimer = setTimeout(function(event){
                document.querySelector(".has-submenu.open").className = "has-submenu"
            }, 1000)
        });
        // activate the submenus on activation (click, keyboard activation, etc) for non sighted users
        el.querySelector('a').addEventListener("click",  function(event){
            if (!this.parentNode.classList.contains("open")) {
                this.parentNode.classList.add("open")
                this.setAttribute('aria-expanded', "true")
            } else {
                this.parentNode.classList.remove("open")
                this.setAttribute('aria-expanded', "false")
            }
            event.preventDefault()
            return false
        });
    });
}
