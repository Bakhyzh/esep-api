package com.esep.transaction;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    // load entries in the same query: no LazyInitializationException, no N+1
    @EntityGraph(attributePaths = "entries")
    Optional<LedgerTransaction> findWithEntriesById(Long id);

    @EntityGraph(attributePaths = "entries")
    Optional<LedgerTransaction> findWithEntriesByCreatedBy_IdAndIdempotencyKey(Long createdById, String idempotencyKey);

    /** A user may see a transaction if at least one of its entries touches one of their accounts. */
    @Query("""
            select count(e) > 0 from LedgerEntry e
            where e.transaction.id = :transactionId and e.account.user.id = :userId
            """)
    boolean isParticipant(@Param("transactionId") Long transactionId, @Param("userId") Long userId);
}
