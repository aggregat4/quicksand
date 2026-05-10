package net.aggregat4.quicksand.jobs;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Store;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbActorRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MailboxActionSyncTest {

  @RegisterExtension
  static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();

  @Test
  void syncsQueuedMarkReadActionToImap() throws Exception {
    String subject = GreenMailUtil.random();
    GreenmailUtils.deliverOneMessage(
        greenMail, subject, "read-sync-body", "from@foo.bar", "to@foo.bar");

    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Mailbox Action Account",
            "localhost",
            greenMail.getImap().getServerSetup().getPort(),
            "testuser",
            "testpassword",
            "localhost",
            greenMail.getSmtp().getServerSetup().getPort(),
            "testuser",
            "testpassword"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbActorRepository(ds));

    Store syncStore = GreenmailUtils.getImapStore(greenMail);
    ImapStoreSync.syncImapFolders(account, syncStore, folderRepository, emailRepository);
    Email message =
        onlySyncedMessage(
            emailRepository, folderRepository.getFolders(account.id()).getFirst().id());
    emailRepository.updateRead(message.header().id(), true);

    Clock clock = Clock.fixed(Instant.parse("2026-03-25T09:15:00Z"), ZoneId.of("UTC"));
    new MailboxActionSync(accountRepository, emailRepository, clock, 60, 60).syncNow();

    assertRemoteSeen();
    assertEquals("SUCCEEDED", queuedActionStatus(ds));
  }

  private static Email onlySyncedMessage(DbEmailRepository emailRepository, int folderId) {
    EmailPage page =
        emailRepository.getMessages(folderId, 10, 0, 0, PageDirection.RIGHT, SortOrder.ASCENDING);
    assertEquals(1, page.emails().size());
    return page.emails().getFirst();
  }

  private static void assertRemoteSeen() throws Exception {
    Store store = GreenmailUtils.getImapStore(greenMail);
    Folder inbox = store.getFolder("INBOX");
    inbox.open(Folder.READ_ONLY);
    try {
      Message message = inbox.getMessage(1);
      assertTrue(message.isSet(Flags.Flag.SEEN));
    } finally {
      inbox.close();
      store.close();
    }
  }

  private static String queuedActionStatus(DataSource ds) throws Exception {
    try (Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement("SELECT status FROM mailbox_action_queue WHERE action_type = ?")) {
      stmt.setString(1, "MARK_READ");
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }
}
