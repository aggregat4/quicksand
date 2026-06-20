import { initMessageListScrollPersistence } from 'quicksand/account/scroll-persist.js'
import { initMessageList } from 'quicksand/account/message-list.js'
import { initMessagePreview } from 'quicksand/account/message-preview.js'
import { initSelectedEmailActions } from 'quicksand/account/email-actions.js'
import { initSelectedDraftComposer } from 'quicksand/account/composer-host.js'
import { initOpenMessageReadState } from 'quicksand/account/mark-read.js'

export function initAccountPage() {
    initMessageListScrollPersistence()
    initMessageList()
    initMessagePreview()
    initSelectedEmailActions()
    initSelectedDraftComposer()
    initOpenMessageReadState()
}
