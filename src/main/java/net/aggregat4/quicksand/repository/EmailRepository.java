package net.aggregat4.quicksand.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;

public interface EmailRepository extends MailboxActionRepository {
  Optional<Email> findById(int id);

  Optional<Integer> findAccountIdByMessageId(int id);

  Optional<Email> findByFolderAndUid(int folderId, long uid);

  Optional<Email> findByRemoteKey(int folderId, long uidValidity, long uid);

  void updateFlags(int id, boolean messageStarred, boolean messageRead);

  void updateRead(int id, boolean messageRead);

  /** Returns an immutable set of IMAP UIDs for messages in the folder. */
  Set<Long> getAllMessageIds(int folderId);

  void updateMessageImapUid(int messageId, long imapUid);

  void removeAllByUid(int folderId, Collection<Long> imapUids);

  void removeBatchByUid(int folderId, List<Long> batch);

  void deleteById(int id);

  void archiveById(int id);

  void markSpamById(int id);

  void moveToFolderById(int id, int targetFolderId);

  int addMessage(int folderId, Email email);

  void addMessages(int folderId, List<Email> emails);

  EmailPage getMessages(
      int folderId,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order);

  int getMessageCount(int accountId, int folderId);

  Map<Integer, Integer> countUnreadByFolder(int accountId);

  int countNewSinceLastView(int folderId);

  long maxReceivedEpochSeconds(int folderId);

  List<EmailHeader> getMessagesNewerThan(
      int folderId, long afterReceivedEpochSeconds, int afterMessageId, int limit);

  Map<Integer, Boolean> getReadFlagsByMessageIds(int accountId, Collection<Integer> messageIds);

  EmailPage searchMessages(
      int accountId,
      String query,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order);

  int getSearchMessageCount(int accountId, String query);
}
