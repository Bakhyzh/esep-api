package com.esep.transaction;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    // load entries in the same query: no LazyInitializationException, no N+1
    @EntityGraph(attributePaths = "entries")
    Optional<LedgerTransaction> findWithEntriesById(Long id);

    @EntityGraph(attributePaths = "entries")
    Optional<LedgerTransaction> findWithEntriesByIdempotencyKey(String idempotencyKey);
}
