package net.aggregat4.quicksand.jobs;

import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import java.util.Objects;
import org.eclipse.angus.mail.imap.IMAPStore;

public final class CondstoreSyncPolicy {

  public enum SyncMode {
    FULL,
    INCREMENTAL
  }

  static final long DEFAULT_FULL_RECONCILE_INTERVAL_SECONDS = 86_400L;

  private CondstoreSyncPolicy() {}

  public static SyncMode resolve(
      boolean condstoreSupported,
      Long highestModSeq,
      long nowEpochS,
      Long lastFullSyncEpochS,
      long fullReconcileIntervalSeconds) {
    if (!condstoreSupported) {
      return SyncMode.FULL;
    }
    if (highestModSeq == null || highestModSeq <= 0) {
      return SyncMode.FULL;
    }
    if (lastFullSyncEpochS == null) {
      return SyncMode.FULL;
    }
    if (nowEpochS - lastFullSyncEpochS >= fullReconcileIntervalSeconds) {
      return SyncMode.FULL;
    }
    return SyncMode.INCREMENTAL;
  }

  public static boolean uidValidityChanged(Long storedUidValidity, long remoteUidValidity) {
    return storedUidValidity != null && !Objects.equals(storedUidValidity, remoteUidValidity);
  }

  public static boolean supportsCondstore(Store store) throws MessagingException {
    if (!(store instanceof IMAPStore imapStore)) {
      return false;
    }
    return imapStore.hasCapability("CONDSTORE") || imapStore.hasCapability("QRESYNC");
  }
}
