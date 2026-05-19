package net.aggregat4.quicksand.tools;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPStore;

public final class ImapCapabilityProber {
  private static final List<CapabilityDefinition> CAPABILITIES =
      List.of(
          new CapabilityDefinition(
              "IMAP4rev1", "Core IMAP revision 1 protocol; required for normal mailbox access."),
          new CapabilityDefinition(
              "STARTTLS",
              "Server can upgrade a cleartext connection to TLS (typical on port 143)."),
          new CapabilityDefinition(
              "LOGINDISABLED",
              "Login is disabled on this port; you may need STARTTLS or a different endpoint."),
          new CapabilityDefinition(
              "AUTH=PLAIN", "Supports plain-text authentication after TLS (common)."),
          new CapabilityDefinition(
              "UIDPLUS",
              "Provides APPENDUID and COPYUID responses so moves/copies can be tracked safely."),
          new CapabilityDefinition(
              "MOVE",
              "Supports UID MOVE for atomic moves between folders (preferred by Quicksand)."),
          new CapabilityDefinition(
              "CONDSTORE",
              "Tracks modification sequences (MODSEQ) for efficient flag and state sync."),
          new CapabilityDefinition(
              "QRESYNC",
              "Quick resync using CONDSTORE plus UID validity (advanced sync optimization)."),
          new CapabilityDefinition(
              "IDLE", "Push-style IDLE extension; server can notify when new mail arrives."),
          new CapabilityDefinition(
              "NAMESPACE",
              "Reports personal/shared/other mailbox namespaces for folder discovery."),
          new CapabilityDefinition(
              "ENABLE", "Allows enabling optional extensions such as CONDSTORE after login."),
          new CapabilityDefinition(
              "UTF8=ACCEPT", "Accepts UTF-8 in mailbox names and search strings."),
          new CapabilityDefinition(
              "SPECIAL-USE", "Advertises standard roles (Trash, Sent, Junk, etc.) on folders."),
          new CapabilityDefinition(
              "LIST-EXTENDED", "Extended LIST responses with richer folder metadata."),
          new CapabilityDefinition(
              "UNSELECT", "Can close the selected mailbox without logging out."),
          new CapabilityDefinition(
              "LITERAL+", "Non-synchronizing literals; faster command pipelining."),
          new CapabilityDefinition("QUOTA", "Exposes mailbox storage quotas."));

  private ImapCapabilityProber() {}

  public static ImapCapabilityReport probe(ImapProbeSettings settings) {
    Store store = null;
    try {
      store = connect(settings);
      return probeConnectedStore((IMAPStore) store, settings);
    } catch (MessagingException | RuntimeException e) {
      return new ImapCapabilityReport(
          settings,
          false,
          abbreviate(e),
          0,
          List.of(),
          "Could not connect; capability probe aborted.");
    } finally {
      if (store != null) {
        try {
          store.close();
        } catch (MessagingException ignored) {
          // best effort
        }
      }
    }
  }

  static ImapCapabilityReport probeConnectedStore(IMAPStore store, ImapProbeSettings settings)
      throws MessagingException {
    List<ImapCapabilityCheck> checks = probeCapabilities(store);
    int mailboxCount = countMailboxes(store);
    return new ImapCapabilityReport(
        settings, true, null, mailboxCount, checks, summarizeQuicksandMoveSupport(checks));
  }

  private static Store connect(ImapProbeSettings settings) throws MessagingException {
    Properties properties = new Properties();
    properties.put("mail.imap.connectiontimeout", "15000");
    properties.put("mail.imap.timeout", "15000");
    if (settings.ssl()) {
      properties.put("mail.imap.ssl.enable", "true");
    }

    Session session = Session.getInstance(properties, null);
    Store store = session.getStore("imap");
    store.connect(settings.host(), settings.port(), settings.username(), settings.password());
    return store;
  }

  private static List<ImapCapabilityCheck> probeCapabilities(IMAPStore store)
      throws MessagingException {
    List<ImapCapabilityCheck> checks = new ArrayList<>();
    for (CapabilityDefinition definition : CAPABILITIES) {
      boolean supported = store.hasCapability(definition.name());
      checks.add(new ImapCapabilityCheck(definition.name(), definition.summary(), supported));
    }
    return checks;
  }

  private static int countMailboxes(Store store) throws MessagingException {
    Folder defaultFolder = store.getDefaultFolder();
    Folder[] folders = defaultFolder.list("*");
    return folders == null ? 0 : folders.length;
  }

  static String summarizeQuicksandMoveSupport(List<ImapCapabilityCheck> checks) {
    boolean move = supported(checks, "MOVE");
    boolean uidplus = supported(checks, "UIDPLUS");
    if (move) {
      return "Remote archive/delete/spam/move actions can use UID MOVE on this server.";
    }
    if (uidplus) {
      return "MOVE is not advertised, but UIDPLUS is. Quicksand could use a COPY + targeted UID "
          + "EXPUNGE fallback, but that is not implemented yet. Move-like sync will fail today.";
    }
    return "Neither MOVE nor UIDPLUS is advertised. Quicksand cannot safely replay remote "
        + "move-like actions on this server; queued moves will fail permanently.";
  }

  private static boolean supported(List<ImapCapabilityCheck> checks, String name) {
    return checks.stream().anyMatch(check -> check.name().equals(name) && check.supported());
  }

  private static String abbreviate(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    String collapsed = message.replaceAll("\\s+", " ").trim();
    return collapsed.length() <= 240 ? collapsed : collapsed.substring(0, 237) + "...";
  }

  private record CapabilityDefinition(String name, String summary) {}
}
