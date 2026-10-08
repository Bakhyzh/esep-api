package com.esep.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Currency;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByUser_IdOrderByIdAsc(Long userId);

    boolean existsByUser_IdAndCurrencyAndStatus(Long userId, Currency currency, AccountStatus status);

    Optional<Account> findByTypeAndCurrencyAndStatus(AccountType type, Currency currency, AccountStatus status);
}
