package net.aggregat4.quicksand.jobs;

import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.repository.MessageRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

class InMemoryMessageRepository implements MessageRepository {

    private final Map<Integer, List<Email>> messages = new HashMap<>();

    @Override
    public Optional<Email> findByMessageId(long uid) {
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
        // do nothing
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
}
