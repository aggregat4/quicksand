package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.Draft;

import java.util.List;
import java.util.Optional;

public interface DraftRepository {
    Draft create(Draft draft);

    Optional<Draft> findById(int id);

    List<Draft> findOpenByAccountId(int accountId);

    void update(Draft draft);

    void delete(int id);
}
