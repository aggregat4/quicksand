package net.aggregat4.quicksand.tools;

import java.util.List;

public record ImapCapabilityReport(
    ImapProbeSettings settings,
    boolean connected,
    String connectionError,
    int mailboxCount,
    List<ImapCapabilityCheck> checks,
    String quicksandMoveSummary) {

  public void printTo(Appendable out) {
    try {
      appendLine(out, "IMAP capability probe");
      appendLine(out, "=====================");
      appendLine(
          out,
          "Server: "
              + settings.host()
              + ":"
              + settings.port()
              + (settings.ssl() ? " (TLS/SSL)" : " (cleartext)"));
      appendLine(out, "Username: " + settings.username());
      appendLine(out, "");

      if (!connected) {
        appendLine(out, "Connection: failed");
        if (connectionError != null && !connectionError.isBlank()) {
          appendLine(out, "Error: " + connectionError);
        }
        return;
      }

      appendLine(out, "Connection: OK");
      appendLine(out, "Mailboxes visible: " + mailboxCount);
      appendLine(out, "");
      appendLine(out, "Quicksand summary");
      appendLine(out, "-----------------");
      appendLine(out, quicksandMoveSummary);
      appendLine(out, "");
      appendLine(out, "Capabilities (plain English)");
      appendLine(out, "----------------------------");
      for (ImapCapabilityCheck check : checks) {
        String status = check.supported() ? "yes" : "no";
        appendLine(out, status + " — " + check.name() + ": " + check.summary());
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to write report", e);
    }
  }

  private static void appendLine(Appendable out, String line) throws java.io.IOException {
    if (out == null) {
      System.out.println(line);
    } else {
      out.append(line).append(System.lineSeparator());
    }
  }
}
