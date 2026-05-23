package net.aggregat4.quicksand.service;

import java.util.List;
import java.util.Optional;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.MailboxSyncStatus;
import net.aggregat4.quicksand.domain.MessageReadState;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.repository.EmailRepository;

public class EmailService {

  private final EmailRepository emailRepository;

  public EmailService(EmailRepository emailRepository) {
    this.emailRepository = emailRepository;
  }

  public EmailPage getMessages(
      int folderId,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order) {
    return emailRepository.getMessages(
        folderId, pageSize, dateTimeOffsetEpochSeconds, offsetMessageId, direction, order);
  }

  public Optional<Email> getMessage(int emailId) {
    return emailRepository.findById(emailId);
  }

  public Optional<Integer> getMessageAccountId(int emailId) {
    return emailRepository.findAccountIdByMessageId(emailId);
  }

  public int getMessageCount(int accountId, int folderId) {
    return emailRepository.getMessageCount(accountId, folderId);
  }

  public List<EmailHeader> getMessagesNewerThan(
      int folderId, long afterReceivedEpochSeconds, int afterMessageId, int limit) {
    return emailRepository.getMessagesNewerThan(
        folderId, afterReceivedEpochSeconds, afterMessageId, limit);
  }

  public List<MessageReadState> getReadStatesForMessages(int accountId, List<Integer> messageIds) {
    return emailRepository.getReadFlagsByMessageIds(accountId, messageIds).entrySet().stream()
        .map(entry -> new MessageReadState(entry.getKey(), entry.getValue()))
        .toList();
  }

  public EmailPage searchMessages(
      int accountId,
      String query,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order) {
    return emailRepository.searchMessages(
        accountId, query, pageSize, dateTimeOffsetEpochSeconds, offsetMessageId, direction, order);
  }

  public int getSearchMessageCount(int accountId, String query) {
    return emailRepository.getSearchMessageCount(accountId, query);
  }

  public MailboxSyncStatus getMailboxSyncStatus(int accountId) {
    return emailRepository.getMailboxSyncStatus(accountId);
  }

  public void updateRead(int id, boolean read) {
    emailRepository.updateRead(id, read);
  }

  public void deleteMessage(int id) {
    emailRepository.deleteById(id);
  }

  public void archiveMessage(int id) {
    emailRepository.archiveById(id);
  }

  public void markSpam(int id) {
    emailRepository.markSpamById(id);
  }

  public void moveMessage(int id, int targetFolderId) {
    emailRepository.moveToFolderById(id, targetFolderId);
  }
}
