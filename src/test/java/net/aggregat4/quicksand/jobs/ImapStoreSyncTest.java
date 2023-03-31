package net.aggregat4.quicksand.jobs;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.NamedFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ImapStoreSyncTest {

    public static final String USERNAME = "waelc";
    public static final String PASSWORD = "somepassword";
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.ALL);

    @Test
    public void naiveFolderSyncAgainstEmptyDatabase() throws MessagingException {
        //Use random content to avoid potential residual lingering problems
        String subject = GreenMailUtil.random();
        String body = GreenMailUtil.random();
        MimeMessage message = GreenMailUtil.createTextEmail("to@foo.bar", "from@foo.bar", subject, body, greenMail.getSmtp().getServerSetup()); // Construct message
        GreenMailUser user = greenMail.setUser("wael@localhost", USERNAME, PASSWORD);
        user.deliver(message);
        assertEquals(1, greenMail.getReceivedMessages().length);

        Account account = new Account(1, "test", "localhost", 1234, USERNAME, PASSWORD, "localhost", 25, USERNAME, PASSWORD);
        Session imapSession = greenMail.getImap().createSession();
        Store store = imapSession.getStore("imap");
        store.connect(USERNAME, PASSWORD);

        InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
        InMemoryMessageRepository messageRepository = new InMemoryMessageRepository();
        assertEquals(0, folderRepository.getFolders(account).size());

        ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
        assertEquals(1, folderRepository.getFolders(account).size());
        NamedFolder inbox = folderRepository.getFolders(account).get(0);
        assertEquals("INBOX", inbox.name());
        assertEquals(1, messageRepository.getAllMessageIds(inbox.id()).size());
        Email email = messageRepository.findByMessageId(messageRepository.getAllMessageIds(inbox.id()).iterator().next()).orElseThrow();
        assertEquals(subject, email.header().subject());
    }

}
