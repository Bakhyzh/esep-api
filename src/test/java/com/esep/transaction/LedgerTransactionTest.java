package com.esep.transaction;

import com.esep.account.Account;
import com.esep.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.esep.transaction.TestAccounts.KZT;
import static com.esep.transaction.TestAccounts.systemAccount;
import static com.esep.transaction.TestAccounts.user;
import static com.esep.transaction.TestAccounts.userAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class LedgerTransactionTest {

    private static final com.esep.user.User CREATOR = user(1);

    @Test
    void transfer_movesMoneyAndCreatesBalancedEntries() {
        Account from = userAccount(1, KZT, "150.0000");
        Account to = userAccount(2, KZT, "10.0000");

        LedgerTransaction tx = LedgerTransaction.transfer(CREATOR, "key-1", "hash", from, to, new BigDecimal("100"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(from.getBalance()).isEqualByComparingTo("50");
        assertThat(to.getBalance()).isEqualByComparingTo("110");

        assertThat(tx.getEntries()).hasSize(2);
        BigDecimal sum = tx.getEntries().stream()
                .map(LedgerEntry::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    void transfer_amountIsNormalizedToFourDecimals() {
        Account from = userAccount(1, KZT, "100.0000");
        Account to = userAccount(2, KZT, "0.0000");

        LedgerTransaction tx = LedgerTransaction.transfer(CREATOR, "key-1", "hash", from, to, new BigDecimal("12.5"));

        assertThat(tx.getEntries()).allSatisfy(e -> assertThat(e.getAmount()).hasToString("12.5000"));
    }

    @Test
    void transfer_insufficientFunds_throwsAndKeepsBalances() {
        Account from = userAccount(1, KZT, "99.9999");
        Account to = userAccount(2, KZT, "0.0000");

        assertThatThrownBy(() -> LedgerTransaction.transfer(CREATOR, "key-1", "hash", from, to, new BigDecimal("100")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient funds");
        assertThat(from.getBalance()).isEqualByComparingTo("99.9999");
        assertThat(to.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void transfer_moreThanFourDecimals_isRejectedNotRounded() {
        Account from = userAccount(1, KZT, "100.0000");
        Account to = userAccount(2, KZT, "0.0000");

        assertThatThrownBy(() -> LedgerTransaction.transfer(CREATOR, "key-1", "hash", from, to, new BigDecimal("0.00001")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("decimal places");
    }

    @Test
    void transfer_nonPositiveAmount_isRejected() {
        Account from = userAccount(1, KZT, "100.0000");
        Account to = userAccount(2, KZT, "0.0000");

        assertThatThrownBy(() -> LedgerTransaction.transfer(CREATOR, "key-1", "hash", from, to, BigDecimal.ZERO))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> LedgerTransaction.transfer(CREATOR, "key-2", "hash", from, to, new BigDecimal("-5")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void transfer_toClosedAccount_isRejected() {
        Account from = userAccount(1, KZT, "100.0000");
        Account to = userAccount(2, KZT, "0.0000");
        to.close();

        assertThatThrownBy(() -> LedgerTransaction.transfer(CREATOR, "key-1", "hash", from, to, new BigDecimal("10")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void deposit_systemAccountGoesNegative() {
        Account funding = systemAccount(100, KZT);
        Account target = userAccount(1, KZT, "0.0000");

        LedgerTransaction tx = LedgerTransaction.deposit(CREATOR, "key-1", "hash", funding, target, new BigDecimal("500"));

        assertThat(tx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(funding.getBalance()).isEqualByComparingTo("-500");
        assertThat(target.getBalance()).isEqualByComparingTo("500");
    }

    @Test
    void transfer_fromClosedAccount_isRejectedAndNothingMoves() {
        Account from = userAccount(1, KZT, "0.0000");
        from.close();
        Account to = userAccount(2, KZT, "0.0000");

        assertThatThrownBy(() -> LedgerTransaction.transfer(CREATOR, "key-1", "hash", from, to, new BigDecimal("10")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("closed");
        assertThat(to.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void entries_cannotBeModifiedFromOutside() {
        LedgerTransaction tx = LedgerTransaction.transfer(CREATOR, "key-1", "hash",
                userAccount(1, KZT, "10.0000"), userAccount(2, KZT, "0.0000"), new BigDecimal("10"));

        // the aggregate guarantees "entries sum to zero"; an add() from outside would break it unnoticed
        assertThatThrownBy(() -> tx.getEntries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void signedAmount_isNegativeForDebitAndPositiveForCredit() {
        LedgerTransaction tx = LedgerTransaction.transfer(CREATOR, "key-1", "hash",
                userAccount(1, KZT, "10.0000"), userAccount(2, KZT, "0.0000"), new BigDecimal("7.5"));

        assertThat(tx.getEntries())
                .extracting(LedgerEntry::getDirection, e -> e.signedAmount().toPlainString())
                .containsExactly(
                        tuple(EntryDirection.DEBIT, "-7.5000"),
                        tuple(EntryDirection.CREDIT, "7.5000"));
    }
}
