package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface EmailRepository {
    Optional<Email> findByMessageUid(long uid);

    void updateFlags(int id, boolean messageStarred, boolean messageRead);

    /**
     * @return A mutable HashSet that can be modified by the client.
     */
    Set<Long> getAllMessageIds(int folderId);

    void removeAllByUid(Collection<Long> localMessageIds);

    void removeBatchByUid(List<Long> batch);

    int addMessage(int folderId, Email email);

    EmailPage getMessages(int folderId, int pageSize, long dateTimeOffsetEpochSeconds, int offsetMessageId, PageDirection direction, SortOrder order);

    int getMessageCount(int accountId, int folderId);
}
