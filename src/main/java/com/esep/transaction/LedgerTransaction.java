package com.esep.transaction;

import com.esep.account.Account;
import com.esep.common.exception.BusinessRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Named LedgerTransaction, not Transaction, to avoid confusion with
 * jakarta.transaction.Transaction / org.hibernate.Transaction / @Transactional.
 * <p>
 * Aggregate root: entries are created only through it, so the
 * "entries sum to zero" invariant is checked in one place.
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerTransaction {

    /** Matches NUMERIC(19,4) in the database. */
    static final int MONEY_SCALE = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries = new ArrayList<>();

    private LedgerTransaction(TransactionType type, String idempotencyKey) {
        this.type = type;
        this.idempotencyKey = idempotencyKey;
        this.status = TransactionStatus.PENDING;
    }

    public static LedgerTransaction transfer(String idempotencyKey, Account from, Account to, BigDecimal amount) {
        return move(TransactionType.TRANSFER, idempotencyKey, from, to, amount);
    }

    /** Money comes from the outside world: the system funding account is debited. */
    public static LedgerTransaction deposit(String idempotencyKey, Account funding, Account target, BigDecimal amount) {
        return move(TransactionType.DEPOSIT, idempotencyKey, funding, target, amount);
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    private static LedgerTransaction move(TransactionType type, String idempotencyKey,
                                          Account from, Account to, BigDecimal amount) {
        BigDecimal normalized = normalize(amount);
        LedgerTransaction tx = new LedgerTransaction(type, idempotencyKey);
        tx.post(from, EntryDirection.DEBIT, normalized);
        tx.post(to, EntryDirection.CREDIT, normalized);
        tx.complete();
        return tx;
    }

    /** The only place where a balance changes: always together with a ledger entry. */
    private void post(Account account, EntryDirection direction, BigDecimal amount) {
        if (direction == EntryDirection.DEBIT) {
            account.debit(amount);
        } else {
            account.credit(amount);
        }
        entries.add(new LedgerEntry(this, account, direction, amount));
    }

    private void complete() {
        BigDecimal sum = entries.stream()
                .map(LedgerEntry::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (entries.size() < 2 || sum.signum() != 0) {
            // a bug in our code, not a user error: fail loudly, @Transactional rolls everything back
            throw new IllegalStateException("Unbalanced transaction: " + entries.size() + " entries, sum " + sum);
        }
        this.status = TransactionStatus.COMPLETED;
    }

    private static BigDecimal normalize(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("Amount must be positive");
        }
        try {
            // UNNECESSARY: never round money silently, reject 10.00001 instead of turning it into 10.0000
            return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BusinessRuleException("Amount must have at most " + MONEY_SCALE + " decimal places");
        }
    }
}
