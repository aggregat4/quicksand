package net.aggregat4.quicksand.jobs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxSyncStatus;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.repository.EmailRepository;

public class InMemoryEmailRepository implements EmailRepository {

  private final Map<Integer, List<Email>> messages = new HashMap<>();
  private final Set<Long> pendingMoveLikeActionSourceUids = new HashSet<>();

  @Override
  public Optional<Email> findById(int id) {
    for (List<Email> emails : messages.values()) {
      for (Email email : emails) {
        if (email.header().id() == id) {
          return Optional.of(email);
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<Integer> findAccountIdByMessageId(int id) {
    throw new UnsupportedOperationException("findAccountIdByMessageId not implemented");
  }

  @Override
  public Optional<Email> findByMessageUid(long uid) {
    for (List<Email> emails : messages.values()) {
      for (Email email : emails) {
        if (email.header().imapUid() == uid) {
          return Optional.of(email);
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public void updateFlags(int id, boolean messageStarred, boolean messageRead) {
    for (List<Email> emails : messages.values()) {
      for (Email email : emails) {
        if (email.header().id() == id) {
          emails.remove(email);
          emails.add(
              new Email(
                  new EmailHeader(
                      email.header().id(),
                      email.header().imapUid(),
                      email.header().actors(),
                      email.header().subject(),
                      email.header().sentDateTime(),
                      email.header().sentDateTimeEpochSeconds(),
                      email.header().receivedDateTime(),
                      email.header().receivedDateTimeEpochSeconds(),
                      email.header().bodyExcerpt(),
                      messageStarred,
                      email.header().attachment(),
                      messageRead),
                  email.plainText(),
                  email.body(),
                  email.attachments(),
                  email.inboundAttachments()));
          return;
        }
      }
    }
  }

  @Override
  public void updateRead(int id, boolean messageRead) {
    for (List<Email> emails : messages.values()) {
      for (Email email : emails) {
        if (email.header().id() == id) {
          emails.remove(email);
          emails.add(
              new Email(
                  new EmailHeader(
                      email.header().id(),
                      email.header().imapUid(),
                      email.header().actors(),
                      email.header().subject(),
                      email.header().sentDateTime(),
                      email.header().sentDateTimeEpochSeconds(),
                      email.header().receivedDateTime(),
                      email.header().receivedDateTimeEpochSeconds(),
                      email.header().bodyExcerpt(),
                      email.header().starred(),
                      email.header().attachment(),
                      messageRead),
                  email.plainText(),
                  email.body(),
                  email.attachments(),
                  email.inboundAttachments()));
          return;
        }
      }
    }
  }

  @Override
  public Set<Long> getAllMessageIds(int folderId) {
    List<Email> emails = messages.get(folderId);
    if (emails == null) {
      return new HashSet<>();
    }
    return emails.stream().map(Email::header).map(EmailHeader::imapUid).collect(Collectors.toSet());
  }

  @Override
  public Set<Long> getPendingMoveLikeActionSourceUids(
      int accountId, String sourceRemoteName, Long sourceUidValidity) {
    return new HashSet<>(pendingMoveLikeActionSourceUids);
  }

  public void addPendingMoveLikeActionSourceUid(long uid) {
    pendingMoveLikeActionSourceUids.add(uid);
  }

  @Override
  public MailboxSyncStatus getMailboxSyncStatus(int accountId) {
    return new MailboxSyncStatus(0, 0, 0, 0, false, List.of());
  }

  @Override
  public List<MailboxActionQueueRow> claimDueMailboxActions(
      java.time.ZonedDateTime now, int limit) {
    return List.of();
  }

  @Override
  public void markMailboxActionSucceeded(int id, java.time.ZonedDateTime now) {}

  @Override
  public void markMailboxActionRetry(
      int id, String error, java.time.ZonedDateTime nextAttempt, java.time.ZonedDateTime now) {}

  @Override
  public void markMailboxActionConflict(int id, String error, java.time.ZonedDateTime now) {}

  @Override
  public void markMailboxActionPermanentFailure(
      int id, String error, java.time.ZonedDateTime now) {}

  @Override
  public Optional<MailboxActionQueueRow> findMailboxAction(int actionId, int accountId) {
    return Optional.empty();
  }

  @Override
  public boolean requestMailboxActionRetry(
      int actionId, int accountId, java.time.ZonedDateTime now) {
    return false;
  }

  @Override
  public boolean dismissMailboxAction(int actionId, int accountId, java.time.ZonedDateTime now) {
    return false;
  }

  @Override
  public boolean abandonMailboxAction(int actionId, int accountId, java.time.ZonedDateTime now) {
    return false;
  }

  @Override
  public boolean rollbackMailboxAction(int actionId, int accountId, java.time.ZonedDateTime now) {
    return false;
  }

  @Override
  public void resolveUnresolvedMailboxActions(
      int accountId,
      net.aggregat4.quicksand.domain.MailboxActionResolutionType resolutionType,
      java.time.ZonedDateTime now) {}

  @Override
  public void clearMirroredMailboxState(int accountId) {}

  @Override
  public int purgeStaleMailboxActionRows(java.time.ZonedDateTime now) {
    return 0;
  }

  @Override
  public void updateMessageImapUid(int messageId, long imapUid) {}

  @Override
  public void enqueueAppendSent(int outboundMessageId) {}

  @Override
  public void scheduleDraftUpsert(int draftId, java.time.ZonedDateTime nextAttemptAt) {}

  @Override
  public void enqueueDraftDelete(int draftId) {}

  @Override
  public void enqueueDraftDelete(java.sql.Connection con, int draftId) {}

  @Override
  public void removeAllByUid(Collection<Long> localMessageIds) {
    for (List<Email> emails : messages.values()) {
      emails.removeIf(email -> localMessageIds.contains(email.header().imapUid()));
    }
  }

  @Override
  public void removeBatchByUid(List<Long> batch) {
    this.removeAllByUid(batch);
  }

  @Override
  public void deleteById(int id) {
    for (List<Email> emails : messages.values()) {
      emails.removeIf(email -> email.header().id() == id);
    }
  }

  @Override
  public void archiveById(int id) {
    moveToFolderById(id, -1);
  }

  @Override
  public void markSpamById(int id) {
    moveToFolderById(id, -2);
  }

  @Override
  public void moveToFolderById(int id, int targetFolderId) {
    Email toMove = null;
    Integer fromFolder = null;
    for (Map.Entry<Integer, List<Email>> entry : messages.entrySet()) {
      for (Email email : entry.getValue()) {
        if (email.header().id() == id) {
          toMove = email;
          fromFolder = entry.getKey();
          break;
        }
      }
      if (toMove != null) {
        break;
      }
    }
    if (toMove != null && fromFolder != null) {
      messages.get(fromFolder).remove(toMove);
      messages.computeIfAbsent(targetFolderId, k -> new ArrayList<>()).add(toMove);
    }
  }

  @Override
  public int addMessage(int folderId, Email email) {
    messages.computeIfAbsent(folderId, k -> new ArrayList<>()).add(email);
    return -1;
  }

  @Override
  public void addMessages(int folderId, List<Email> emails) {
    messages.computeIfAbsent(folderId, k -> new ArrayList<>()).addAll(emails);
  }

  @Override
  public EmailPage getMessages(
      int folderId,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order) {
    throw new UnsupportedOperationException("getMessages not implemented");
  }

  @Override
  public int getMessageCount(int accountId, int folderId) {
    return messages.getOrDefault(folderId, List.of()).size();
  }

  @Override
  public Map<Integer, Integer> countUnreadByFolder(int accountId) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (Map.Entry<Integer, List<Email>> entry : messages.entrySet()) {
      int unread = (int) entry.getValue().stream().filter(email -> !email.header().read()).count();
      if (unread > 0) {
        counts.put(entry.getKey(), unread);
      }
    }
    return counts;
  }

  @Override
  public int countNewSinceLastView(int folderId) {
    throw new UnsupportedOperationException("countNewSinceLastView not implemented");
  }

  @Override
  public long maxReceivedEpochSeconds(int folderId) {
    return messages.getOrDefault(folderId, List.of()).stream()
        .mapToLong(email -> email.header().receivedDateTimeEpochSeconds())
        .max()
        .orElse(0L);
  }

  @Override
  public List<EmailHeader> getMessagesNewerThan(
      int folderId, long afterReceivedEpochSeconds, int afterMessageId, int limit) {
    return messages.getOrDefault(folderId, List.of()).stream()
        .filter(
            email -> {
              long received = email.header().receivedDateTimeEpochSeconds();
              int id = email.header().id();
              return received > afterReceivedEpochSeconds
                  || (received == afterReceivedEpochSeconds && id > afterMessageId);
            })
        .sorted(
            (left, right) -> {
              int byDate =
                  Long.compare(
                      right.header().receivedDateTimeEpochSeconds(),
                      left.header().receivedDateTimeEpochSeconds());
              if (byDate != 0) {
                return byDate;
              }
              return Integer.compare(right.header().id(), left.header().id());
            })
        .limit(limit)
        .map(Email::header)
        .toList();
  }

  @Override
  public Map<Integer, Boolean> getReadFlagsByMessageIds(
      int accountId, Collection<Integer> messageIds) {
    Map<Integer, Boolean> readFlags = new HashMap<>();
    for (List<Email> emails : messages.values()) {
      for (Email email : emails) {
        if (messageIds.contains(email.header().id())) {
          readFlags.put(email.header().id(), email.header().read());
        }
      }
    }
    return readFlags;
  }

  @Override
  public EmailPage searchMessages(
      int accountId,
      String query,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order) {
    throw new UnsupportedOperationException("searchMessages not implemented");
  }

  @Override
  public int getSearchMessageCount(int accountId, String query) {
    throw new UnsupportedOperationException("getSearchMessageCount not implemented");
  }
}
