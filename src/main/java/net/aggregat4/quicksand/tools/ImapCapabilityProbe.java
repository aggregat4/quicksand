package net.aggregat4.quicksand.tools;

/**
 * Command-line utility to connect to an IMAP server and summarize advertised capabilities in plain
 * English.
 *
 * <p>Example:
 *
 * <pre>
 * mvn -q -DskipTests package
 * java -cp "target/quicksand.jar:target/libs/*" net.aggregat4.quicksand.tools.ImapCapabilityProbe \
 *   --host imap.example.com --port 993 --user you@example.com --password 'secret'
 * </pre>
 */
public final class ImapCapabilityProbe {
  private ImapCapabilityProbe() {}

  public static void main(String[] args) {
    if (args.length == 0 || containsFlag(args, "--help") || containsFlag(args, "-h")) {
      printUsage(System.out);
      System.exit(0);
    }
    try {
      ImapProbeSettings settings = parseArgs(args);
      warnAboutPasswordOnCommandLine();
      ImapCapabilityReport report = ImapCapabilityProber.probe(settings);
      report.printTo(System.out);
      System.exit(report.connected() ? 0 : 1);
    } catch (IllegalArgumentException e) {
      System.err.println("Error: " + e.getMessage());
      System.err.println();
      printUsage(System.err);
      System.exit(2);
    }
  }

  private static void warnAboutPasswordOnCommandLine() {
    System.err.println(
        "Note: passing --password on the command line may leave the password in your shell"
            + " history.");
    System.err.println();
  }

  static ImapProbeSettings parseArgs(String[] args) {
    String host = requiredOption(args, "--host");
    String user = requiredOption(args, "--user");
    String password = requiredOption(args, "--password");
    boolean ssl = !containsFlag(args, "--no-ssl");
    int port = optionalIntOption(args, "--port").orElse(ssl ? 993 : 143);

    if (containsUnknownArgs(args)) {
      throw new IllegalArgumentException("unrecognized arguments: use --help for usage");
    }

    return new ImapProbeSettings(host, port, user, password, ssl);
  }

  private static boolean containsUnknownArgs(String[] args) {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("-")) {
        if (arg.equals("--host")
            || arg.equals("--port")
            || arg.equals("--user")
            || arg.equals("--password")
            || arg.equals("--no-ssl")
            || arg.equals("--help")
            || arg.equals("-h")) {
          continue;
        }
        return true;
      }
    }
    return false;
  }

  private static boolean containsFlag(String[] args, String flag) {
    for (String arg : args) {
      if (arg.equals(flag)) {
        return true;
      }
    }
    return false;
  }

  private static String requiredOption(String[] args, String name) {
    return optionalOption(args, name)
        .orElseThrow(() -> new IllegalArgumentException("missing required option " + name));
  }

  private static java.util.OptionalInt optionalIntOption(String[] args, String name) {
    return optionalOption(args, name)
        .map(value -> java.util.OptionalInt.of(Integer.parseInt(value)))
        .orElse(java.util.OptionalInt.empty());
  }

  private static java.util.Optional<String> optionalOption(String[] args, String name) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals(name)) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("option " + name + " requires a value");
        }
        return java.util.Optional.of(args[i + 1]);
      }
    }
    return java.util.Optional.empty();
  }

  private static void printUsage(Appendable out) {
    try {
      out.append(
              """
              Usage:
                ImapCapabilityProbe --host HOST --user USER --password PASS [options]

              Options:
                --host HOST       IMAP server hostname (required)
                --port PORT       IMAP port (default: 993 with TLS, 143 with --no-ssl)
                --user USER       IMAP username (required)
                --password PASS   IMAP password (required)
                --no-ssl          Use cleartext IMAP (default port 143)
                --help            Show this help

              Examples:
                ImapCapabilityProbe --host imap.fastmail.com --user me@fastmail.com --password PASS
                ImapCapabilityProbe --host localhost --port 3143 --no-ssl --user testuser --password testpassword
              """)
          .append(System.lineSeparator());
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
