package com.esep.account;

import com.esep.common.exception.BusinessRuleException;
import com.esep.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    @Column(nullable = false)
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public Account(User user, Currency currency) {
        this.user = user;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(4);
        this.status = AccountStatus.ACTIVE;
    }

    public void close() {
        if (status == AccountStatus.CLOSED) {
            throw new BusinessRuleException("Account " + id + " is already closed");
        }
        // compareTo, not equals: 0.0000 and 0 are not equal by equals() because of different scale
        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessRuleException("Account " + id + " has non-zero balance and cannot be closed");
        }
        this.status = AccountStatus.CLOSED;
        this.closedAt = Instant.now();
    }
}
