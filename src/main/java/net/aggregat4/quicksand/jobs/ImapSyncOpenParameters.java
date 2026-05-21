package net.aggregat4.quicksand.jobs;

import net.aggregat4.quicksand.domain.NamedFolder;

record ImapSyncOpenParameters(
    boolean condstoreSupported, boolean qresyncSupported, Long uidValidity, Long highestModSeq) {

  static ImapSyncOpenParameters forFolder(
      NamedFolder localFolder, boolean condstoreSupported, boolean qresyncSupported) {
    return new ImapSyncOpenParameters(
        condstoreSupported,
        qresyncSupported,
        localFolder.uidValidity(),
        localFolder.highestModSeq());
  }
}
