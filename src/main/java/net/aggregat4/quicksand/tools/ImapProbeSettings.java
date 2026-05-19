package net.aggregat4.quicksand.tools;

public record ImapProbeSettings(
    String host, int port, String username, String password, boolean ssl) {
  public ImapProbeSettings {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("host is required");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username is required");
    }
    if (password == null) {
      throw new IllegalArgumentException("password is required");
    }
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
  }
}
