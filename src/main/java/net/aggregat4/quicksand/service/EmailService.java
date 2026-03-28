package net.aggregat4.quicksand.service;

import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.repository.EmailRepository;

import java.util.Optional;

public class EmailService {

    private final EmailRepository emailRepository;

    public EmailService(EmailRepository emailRepository) {
        this.emailRepository = emailRepository;
    }

    public EmailPage getMessages(int folderId, int pageSize, long dateTimeOffsetEpochSeconds, int offsetMessageId, PageDirection direction, SortOrder order) {
        return emailRepository.getMessages(folderId, pageSize, dateTimeOffsetEpochSeconds, offsetMessageId, direction, order);
    }

    public Optional<Email> getMessage(int emailId) {
        return emailRepository.findById(emailId);
    }

    public int getMessageCount(int accountId, int folderId) {
        return emailRepository.getMessageCount(accountId, folderId);
    }
}
