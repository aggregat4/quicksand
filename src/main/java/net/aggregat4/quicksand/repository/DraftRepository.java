package net.aggregat4.quicksand.repository;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import net.aggregat4.quicksand.domain.Draft;

public interface DraftRepository {
  Draft create(Draft draft);

  Optional<Draft> findById(int id);

  Optional<Draft> findById(Connection con, int id);

  List<Draft> findOpenByAccountId(int accountId);

  void update(Draft draft);

  void updateRemoteIdentity(int draftId, long remoteImapUid, long remoteUidValidity);

  void delete(int id);

  void delete(Connection con, int id);
}
