package net.aggregat4.quicksand.jobs;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ImapStoreSyncTest {
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.ALL);

    @Test
    public void testNaiveFolderSyncAgainstEmptyDatabase() {
        //Use random content to avoid potential residual lingering problems
        final String subject = GreenMailUtil.random();
        final String body = GreenMailUtil.random();
        MimeMessage message = GreenMailUtil.createTextEmail("to@foo.bar", "from@foo.bar", subject, body, greenMail.getSmtp().getServerSetup()); // Construct message
        GreenMailUser user = greenMail.setUser("wael@localhost", "waelc", "soooosecret");
        user.deliver(message);
        assertEquals(1, greenMail.getReceivedMessages().length);
    }
}
