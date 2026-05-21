package net.aggregat4.quicksand.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CondstoreSyncPolicyTest {

  @Test
  void usesFullSyncWhenCondstoreIsUnsupported() {
    assertEquals(
        CondstoreSyncPolicy.SyncMode.FULL,
        CondstoreSyncPolicy.resolve(false, 100L, 1_000L, 900L, 86_400L));
  }

  @Test
  void usesFullSyncWithoutCheckpoint() {
    assertEquals(
        CondstoreSyncPolicy.SyncMode.FULL,
        CondstoreSyncPolicy.resolve(true, null, 1_000L, 900L, 86_400L));
    assertEquals(
        CondstoreSyncPolicy.SyncMode.FULL,
        CondstoreSyncPolicy.resolve(true, 100L, 1_000L, null, 86_400L));
  }

  @Test
  void usesIncrementalSyncWithRecentFullReconcile() {
    assertEquals(
        CondstoreSyncPolicy.SyncMode.INCREMENTAL,
        CondstoreSyncPolicy.resolve(true, 250L, 1_000L, 900L, 86_400L));
  }

  @Test
  void usesFullSyncWhenReconcileIntervalElapsed() {
    assertEquals(
        CondstoreSyncPolicy.SyncMode.FULL,
        CondstoreSyncPolicy.resolve(true, 250L, 100_000L, 1_000L, 86_400L));
  }

  @Test
  void detectsUidValidityChanges() {
    assertTrue(CondstoreSyncPolicy.uidValidityChanged(10L, 20L));
    assertFalse(CondstoreSyncPolicy.uidValidityChanged(null, 20L));
    assertFalse(CondstoreSyncPolicy.uidValidityChanged(20L, 20L));
  }
}
