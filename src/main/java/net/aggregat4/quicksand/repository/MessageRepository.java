package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.Email;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MessageRepository {
    Optional<Email> findByMessageId(long uid);

    void updateFlags(int id, boolean messageStarred, boolean messageRead);

    /**
     * @return A mutable HashSet that can be modified by the client.
     */
    Set<Long> getAllMessageIds(int folderId);

    void removeAllByUid(Collection<Long> localMessageIds);

    void removeBatchByUid(List<Long> batch);

    int addMessage(int folderId, Email email);
}
