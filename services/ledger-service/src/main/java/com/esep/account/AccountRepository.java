package com.esep.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Currency;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByUser_IdOrderByIdAsc(Long userId);

    boolean existsByIdAndUser_Id(Long id, Long userId);

    boolean existsByUser_IdAndCurrencyAndStatus(Long userId, Currency currency, AccountStatus status);

    /**
     * SELECT ... FOR UPDATE: the row stays locked until the current transaction ends.
     * Other writers (and other FOR UPDATE readers) wait; plain SELECTs are not blocked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    // scalar projections: they do NOT put an Account into the persistence context,
    // so a later findByIdForUpdate() returns fresh, locked data instead of a stale cached entity
    @Query("select a.currency from Account a where a.id = :id")
    Optional<Currency> findCurrencyById(@Param("id") Long id);

    @Query("""
            select a.id from Account a
            where a.type = com.esep.account.AccountType.SYSTEM
              and a.status = com.esep.account.AccountStatus.ACTIVE
              and a.currency = :currency
            """)
    Optional<Long> findSystemAccountId(@Param("currency") Currency currency);
}
