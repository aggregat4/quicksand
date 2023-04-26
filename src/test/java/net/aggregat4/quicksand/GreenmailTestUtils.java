package net.aggregat4.quicksand;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import net.aggregat4.quicksand.domain.Account;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GreenmailTestUtils {

    private static final String USERNAME = "waelc";
    private static final String PASSWORD = "somepassword";
    private static final Account account = new Account(1, "test", "localhost", 1234, USERNAME, PASSWORD, "localhost", 25, USERNAME, PASSWORD);
    private static final String EMAIL = "wael@localhost";

    public static Store getImapStore(GreenMailExtension greenMail) throws MessagingException {
        Session imapSession = greenMail.getImap().createSession();
        Store store = imapSession.getStore("imap");
        store.connect(USERNAME, PASSWORD);
        return store;
    }

    public static void deliverOneMessage(GreenMailExtension greenMail, String subject, String body, String from, String to) {
        deliverMessages(greenMail, subject, body, from, to, 1);
    }

    public static void deliverMessages(GreenMailExtension greenMail, String subject, String body, String from, String to, int count) {
        MimeMessage message = GreenMailUtil.createTextEmail(to, from, subject, body, greenMail.getSmtp().getServerSetup()); // Construct message
        GreenMailUser user = greenMail.setUser(EMAIL, USERNAME, PASSWORD);
        for (int i = 0; i < count; i++) {
            user.deliver(message);
        }
        assertEquals(count, greenMail.getReceivedMessages().length);
    }

    public static Account getAccount() {
        return account;
    }
}
