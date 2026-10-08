package com.esep.account;

import com.esep.account.dto.AccountResponse;
import com.esep.account.dto.CreateAccountRequest;
import com.esep.common.exception.BusinessRuleException;
import com.esep.common.exception.ConflictException;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.user.User;
import com.esep.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.userId()));
        Currency currency = parseCurrency(request.currency());

        // fast path: a friendly error in the common case
        if (accountRepository.existsByUser_IdAndCurrencyAndStatus(user.getId(), currency, AccountStatus.ACTIVE)) {
            throw duplicateAccount(user.getId(), currency);
        }

        try {
            // saveAndFlush: send INSERT now, so a unique-index violation is thrown here and not at commit
            Account account = accountRepository.saveAndFlush(new Account(user, currency));
            return AccountResponse.from(account);
        } catch (DataIntegrityViolationException e) {
            // race: two parallel requests both passed the exists-check, the DB unique index stopped the second one
            throw duplicateAccount(user.getId(), currency);
        }
    }

    public AccountResponse getById(Long id) {
        return AccountResponse.from(findAccount(id));
    }

    public List<AccountResponse> getByUser(Long userId) {
        return accountRepository.findAllByUser_IdOrderByIdAsc(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional
    public AccountResponse close(Long id) {
        Account account = findAccount(id);
        account.close();
        // no save() needed: the entity is managed, Hibernate flushes the change on commit (dirty checking)
        return AccountResponse.from(account);
    }

    private Account findAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    private static Currency parseCurrency(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Unknown currency: " + code);
        }
    }

    private static ConflictException duplicateAccount(Long userId, Currency currency) {
        return new ConflictException("User " + userId + " already has an active " + currency + " account");
    }
}
