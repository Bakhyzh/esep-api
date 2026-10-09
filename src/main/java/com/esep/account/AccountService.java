package com.esep.account;

import com.esep.account.dto.AccountResponse;
import com.esep.account.dto.CreateAccountRequest;
import com.esep.common.exception.BusinessRuleException;
import com.esep.common.exception.ConflictException;
import com.esep.common.exception.ErrorCode;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.security.CurrentUser;
import com.esep.user.User;
import com.esep.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
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
    public AccountResponse create(CurrentUser currentUser, CreateAccountRequest request) {
        // a valid token can outlive its user (deleted after login), so the user is still looked up
        User user = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("User", currentUser.id()));
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

    public AccountResponse getById(CurrentUser currentUser, Long id) {
        return AccountResponse.from(findAccessibleAccount(currentUser, id));
    }

    /** userId == null means "my accounts"; only an admin may list somebody else's. */
    public List<AccountResponse> getByUser(CurrentUser currentUser, Long userId) {
        Long ownerId = userId != null ? userId : currentUser.id();
        if (!currentUser.canAccess(ownerId)) {
            throw new AccessDeniedException("You can only list your own accounts");
        }
        return accountRepository.findAllByUser_IdOrderByIdAsc(ownerId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional
    public AccountResponse close(CurrentUser currentUser, Long id) {
        Account account = findAccessibleAccount(currentUser, id);
        account.close();
        // no save() needed: the entity is managed, Hibernate flushes the change on commit (dirty checking)
        return AccountResponse.from(account);
    }

    /**
     * Somebody else's account answers 404, not 403: a 403 would confirm that the id exists
     * and let an attacker enumerate accounts.
     */
    private Account findAccessibleAccount(CurrentUser currentUser, Long id) {
        return accountRepository.findById(id)
                // getUser().getId() on a lazy proxy does not query the users table
                .filter(account -> currentUser.canAccess(account.getUser().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    private static Currency parseCurrency(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(ErrorCode.UNSUPPORTED_CURRENCY, "Unknown currency: " + code);
        }
    }

    private static ConflictException duplicateAccount(Long userId, Currency currency) {
        return new ConflictException(ErrorCode.DUPLICATE_ACCOUNT, "User " + userId + " already has an active " + currency + " account");
    }
}
