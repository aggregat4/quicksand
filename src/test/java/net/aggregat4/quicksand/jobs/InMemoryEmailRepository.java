package net.aggregat4.quicksand.jobs;

import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

class InMemoryEmailRepository implements EmailRepository {

    private final Map<Integer, List<Email>> messages = new HashMap<>();

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
                    emails.add(new Email(new EmailHeader(email.header().id(), email.header().imapUid(), email.header().actors(), email.header().subject(), email.header().sentDateTime(), email.header().sentDateTimeEpochSeconds(), email.header().receivedDateTime(), email.header().receivedDateTimeEpochSeconds(), email.header().bodyExcerpt(), messageStarred, email.header().attachment(), messageRead), email.plainText(), email.body(), email.attachments()));
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
    public int addMessage(int folderId, Email email) {
        messages.computeIfAbsent(folderId, k -> new ArrayList<>()).add(email);
        return -1;
    }

    @Override
    public EmailPage getMessages(int folderId, int pageSize, long dateTimeOffsetEpochSeconds, int offsetMessageId, PageDirection direction, SortOrder order) {
        throw new UnsupportedOperationException("getMessages not implemented");
    }

    @Override
    public int getMessageCount(int accountId, int folderId) {
        return 1234;
    }
}
