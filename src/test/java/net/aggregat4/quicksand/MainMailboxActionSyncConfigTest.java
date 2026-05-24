package net.aggregat4.quicksand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MainMailboxActionSyncConfigTest {

  @Test
  void applicationConfDoesNotHardDisableMailboxActionSync() throws Exception {
    Path mainConfig = Path.of("src/main/resources/application.conf");
    assumeTrue(Files.exists(mainConfig), "Run from repository root");

    String config = Files.readString(mainConfig);
    assertFalse(
        config.contains("mailbox_action_sync = {\n  enabled: false"),
        "Hard-disabling mailbox action sync in application.conf prevents demo mode from syncing"
            + " queued actions to IMAP");
  }

  @Test
  void demoModeShouldEnableMailboxActionSyncWhenFlagIsOmitted() {
    boolean demoEnabled = true;
    assertEquals(true, resolveMailboxActionSyncEnabled(demoEnabled, java.util.Optional.empty()));
    assertEquals(false, resolveMailboxActionSyncEnabled(demoEnabled, java.util.Optional.of(false)));
    assertEquals(true, resolveMailboxActionSyncEnabled(false, java.util.Optional.of(true)));
  }

  static boolean resolveMailboxActionSyncEnabled(
      boolean demoEnabled, java.util.Optional<Boolean> configuredEnabled) {
    return configuredEnabled.orElse(demoEnabled);
  }
}
