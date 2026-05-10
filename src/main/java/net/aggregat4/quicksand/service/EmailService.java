package net.aggregat4.quicksand.service;

import java.util.Optional;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
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

  public int getMessageCount(int accountId, int folderId) {
    return emailRepository.getMessageCount(accountId, folderId);
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

  public void updateRead(int id, boolean read) {
    emailRepository.updateRead(id, read);
  }

  public void deleteMessage(int id) {
    emailRepository.deleteById(id);
  }

  public void archiveMessage(int id) {
    emailRepository.archiveById(id);
  }
}
