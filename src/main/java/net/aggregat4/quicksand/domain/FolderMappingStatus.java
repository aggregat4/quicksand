package net.aggregat4.quicksand.domain;

import java.util.List;

public enum FolderMappingStatus {
  AUTO_DETECTED,
  USER_CONFIRMED,
  MISSING,
  CONFLICT;

  public static final List<FolderMappingStatus> CONFIGURED = List.of(AUTO_DETECTED, USER_CONFIRMED);
}
