package net.aggregat4.quicksand.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.Store;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ImapCapabilityProbeTest {

  @RegisterExtension
  static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();

  @Test
  void parseArgsRequiresHostUserAndPassword() {
    ImapProbeSettings settings =
        ImapCapabilityProbe.parseArgs(
            new String[] {
              "--host", "imap.example.com",
              "--user", "alice",
              "--password", "secret"
            });
    assertEquals("imap.example.com", settings.host());
    assertEquals(993, settings.port());
    assertEquals("alice", settings.username());
    assertEquals("secret", settings.password());
    assertTrue(settings.ssl());
  }

  @Test
  void parseArgsHonoursPortAndNoSsl() {
    ImapProbeSettings settings =
        ImapCapabilityProbe.parseArgs(
            new String[] {
              "--host",
              "localhost",
              "--port",
              "3143",
              "--no-ssl",
              "--user",
              "bob",
              "--password",
              "pw"
            });
    assertEquals(3143, settings.port());
    assertFalse(settings.ssl());
  }

  @Test
  void parseArgsRejectsUnknownOptions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ImapCapabilityProbe.parseArgs(
                new String[] {"--host", "h", "--user", "u", "--password", "p", "--verbose"}));
  }

  @Test
  void probeReportsMoveSupportAgainstGreenMail() throws Exception {
    GreenmailUtils.deliverOneMessage(
        greenMail, GreenMailUtil.random(), "probe-body", "from@foo.bar", "to@foo.bar");
    int imapPort = greenMail.getImap().getServerSetup().getPort();
    ImapProbeSettings settings =
        new ImapProbeSettings("localhost", imapPort, "testuser", "testpassword", false);
    Store store = GreenmailUtils.getImapStore(greenMail);
    try {
      ImapCapabilityReport report =
          ImapCapabilityProber.probeConnectedStore((IMAPStore) store, settings);
      assertTrue(report.connected());
      assertTrue(report.mailboxCount() > 0);
      assertTrue(
          report.checks().stream()
              .anyMatch(check -> check.name().equals("MOVE") && check.supported()));
      assertTrue(report.quicksandMoveSummary().contains("UID MOVE"));
    } finally {
      store.close();
    }
  }

  @Test
  void summarizeQuicksandMoveSupportExplainsMissingMove() {
    String summary =
        ImapCapabilityProber.summarizeQuicksandMoveSupport(
            java.util.List.of(
                new ImapCapabilityCheck("MOVE", "", false),
                new ImapCapabilityCheck("UIDPLUS", "", true)));
    assertTrue(summary.contains("UIDPLUS"));
    assertTrue(summary.contains("not implemented"));
  }
}
