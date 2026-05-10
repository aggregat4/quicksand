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
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.repository.EmailRepository;

public class InMemoryEmailRepository implements EmailRepository {

  private final Map<Integer, List<Email>> messages = new HashMap<>();

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
                  email.attachments()));
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
                  email.attachments()));
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
    return 1234;
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
