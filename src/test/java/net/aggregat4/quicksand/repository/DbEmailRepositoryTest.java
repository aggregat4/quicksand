package net.aggregat4.quicksand.repository;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.jobs.ImapStoreSync;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.*;

public class DbEmailRepositoryTest {

    @RegisterExtension
    static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();

    @Test
    public void emailQueryForDeliveredEmail() throws MessagingException, SQLException, IOException {
        long startOfTestTimestamp = System.currentTimeMillis() / 1000;
        long startOfTestPlusOneHour = startOfTestTimestamp + 3600;
        String subject = GreenMailUtil.random();
        String body = GreenMailUtil.random();
        String from = "from@foo.bar";
        String to = "to@foo.bar";
        GreenmailUtils.deliverMessages(greenMail, subject, body, from, to, 13);
        Store store = GreenmailUtils.getImapStore(greenMail);

        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);
        DbFolderRepository folderRepository = new DbFolderRepository(ds);
        DbActorRepository actorRepository = new DbActorRepository(ds);
        DbEmailRepository emailRepository = new DbEmailRepository(ds, actorRepository);

        Account account = GreenmailUtils.getAccount();
        assertEquals(0, folderRepository.getFolders(account.id()).size());
        ImapStoreSync.syncImapFolders(account, store, folderRepository, emailRepository);
        assertEquals(1, folderRepository.getFolders(account.id()).size());
        assertEquals("INBOX", folderRepository.getFolders(account.id()).get(0).name());

        int pageSize = 5;
        // We retrieve the first page of messages and verify that it is as expected
        EmailPage messages = emailRepository.getMessages(folderRepository.getFolders(account.id()).get(0).id(), pageSize, 0, 0, PageDirection.RIGHT, SortOrder.ASCENDING);
        assertEquals(pageSize, messages.emails().size());
        Email message = messages.emails().get(0);
        assertEquals(from, message.header().getSender().emailAddress());
        assertEquals(to, message.header().getRecipients().get(0).emailAddress());
        long receivedDateTimeEpochSeconds = message.header().receivedDateTimeEpochSeconds();
        assertTrue(receivedDateTimeEpochSeconds >= startOfTestTimestamp && receivedDateTimeEpochSeconds <= startOfTestPlusOneHour, "The received timestamp shoud be less than one hour after the start of the test");
        long sentDateTimeEpochSeconds = message.header().sentDateTimeEpochSeconds();
        assertTrue(sentDateTimeEpochSeconds >= startOfTestTimestamp && sentDateTimeEpochSeconds <= startOfTestPlusOneHour, "The sent timestamp shoud be less than one hour after the start of the test");
        assertFalse(messages.hasLeft());
        assertTrue(messages.hasRight());
        // Retrieve the second page of emails since the page size is 5 and we have 13 total messages there is still a full page left
        Email rightOffsetMessage = messages.emails().get(messages.emails().size() - 1);
        messages = emailRepository.getMessages(folderRepository.getFolders(account.id()).get(0).id(), pageSize, rightOffsetMessage.header().receivedDateTimeEpochSeconds(), rightOffsetMessage.header().id(), PageDirection.RIGHT, SortOrder.ASCENDING);
        assertEquals(pageSize, messages.emails().size());
        assertTrue(messages.hasLeft());
        assertTrue(messages.hasRight());
        // And now navigate to the last page, there should only be the remainder of messages (3) left
        rightOffsetMessage = messages.emails().get(messages.emails().size() - 1);
        messages = emailRepository.getMessages(folderRepository.getFolders(account.id()).get(0).id(), pageSize, rightOffsetMessage.header().receivedDateTimeEpochSeconds(), rightOffsetMessage.header().id(), PageDirection.RIGHT, SortOrder.ASCENDING);
        assertEquals(3, messages.emails().size());
        assertTrue(messages.hasLeft());
        assertFalse(messages.hasRight());
    }
}
