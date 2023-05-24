package net.aggregat4.quicksand.jobs;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.NamedFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.*;

public class ImapStoreSyncTest {

    @RegisterExtension
    static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();


    @Test
    public void naiveFolderSyncAgainstEmptyDatabase() throws MessagingException {
        String subject = GreenMailUtil.random();
        String body = GreenMailUtil.random();
        GreenmailUtils.deliverOneMessage(greenMail, subject, body, "from@foo.bar", "to@foo.bar");
        Store store = GreenmailUtils.getImapStore(greenMail);

        InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
        InMemoryEmailRepository messageRepository = new InMemoryEmailRepository();

        Account account = GreenmailUtils.getAccount();
        assertEquals(0, folderRepository.getFolders(account.id()).size());
        // Sync the imap store and verify that we now have one message in the inbox locally
        ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
        assertEquals(1, folderRepository.getFolders(account.id()).size());
        NamedFolder inbox = folderRepository.getFolders(account.id()).get(0);
        assertEquals("INBOX", inbox.name());
        assertEquals(1, messageRepository.getAllMessageIds(inbox.id()).size());
        Email email = messageRepository.findByMessageUid(messageRepository.getAllMessageIds(inbox.id()).iterator().next()).orElseThrow();
        assertEquals(subject, email.header().subject());
        assertFalse(email.header().read());
        // TODO: body handling
        // TODO: attachment handling
        // TODO: message update handling

        Folder imapFolder = store.getFolder("INBOX");
        if (imapFolder.isOpen()) {
            imapFolder.close();
        }
        // delete all messages in the inbox
        imapFolder.open(Folder.READ_WRITE);
        Message message = imapFolder.getMessage(1);

        // mark the message as read
        message.setFlag(Flags.Flag.SEEN, true);
        assertEquals(1, imapFolder.getMessageCount());
        ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
        // the INBOX folder can not be deleted
        assertEquals(1, folderRepository.getFolders(account.id()).size());
        // but the messages should all be gone
        assertEquals(1, messageRepository.getAllMessageIds(inbox.id()).size());
        email = messageRepository.findByMessageUid(messageRepository.getAllMessageIds(inbox.id()).iterator().next()).orElseThrow();
        assertTrue(email.header().read());

        // mark the message as deleted
        message.setFlag(Flags.Flag.DELETED, true);
        imapFolder.expunge();
        assertEquals(0, imapFolder.getMessageCount());
        ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
        // the INBOX folder can not be deleted
        assertEquals(1, folderRepository.getFolders(account.id()).size());
        // but the messages should all be gone
        assertEquals(0, messageRepository.getAllMessageIds(inbox.id()).size());
    }

}
