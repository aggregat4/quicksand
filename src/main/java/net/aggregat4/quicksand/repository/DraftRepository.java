package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.Draft;

import java.util.Optional;

public interface DraftRepository {
    Draft create(Draft draft);

    Optional<Draft> findById(int id);

    void update(Draft draft);

    void delete(int id);
}
