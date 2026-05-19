package net.aggregat4.quicksand.repository;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxSyncStatus;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;

public interface EmailRepository {
  Optional<Email> findById(int id);

  Optional<Integer> findAccountIdByMessageId(int id);

  Optional<Email> findByMessageUid(long uid);

  void updateFlags(int id, boolean messageStarred, boolean messageRead);

  void updateRead(int id, boolean messageRead);

  /** Returns a mutable HashSet that can be modified by the client. */
  Set<Long> getAllMessageIds(int folderId);

  Set<Long> getPendingMoveLikeActionSourceUids(
      int accountId, String sourceRemoteName, Long sourceUidValidity);

  MailboxSyncStatus getMailboxSyncStatus(int accountId);

  List<MailboxActionQueueRow> claimDueMailboxActions(ZonedDateTime now, int limit);

  void markMailboxActionSucceeded(int id, ZonedDateTime now);

  void markMailboxActionRetry(int id, String error, ZonedDateTime nextAttempt, ZonedDateTime now);

  void markMailboxActionConflict(int id, String error, ZonedDateTime now);

  void markMailboxActionPermanentFailure(int id, String error, ZonedDateTime now);

  void updateMessageImapUid(int messageId, long imapUid);

  void enqueueAppendSent(int outboundMessageId);

  void removeAllByUid(Collection<Long> localMessageIds);

  void removeBatchByUid(List<Long> batch);

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
