package com.esep.transaction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The fingerprint decides between "replay" and "IDEMPOTENCY_KEY_REUSED": it must follow the money, not the JSON. */
class RequestFingerprintTest {

    @Test
    void sameAmountWrittenDifferently_givesTheSameHash() {
        String plain = RequestFingerprint.of(TransactionType.TRANSFER, 1L, 2L, new BigDecimal("100"));

        assertThat(RequestFingerprint.of(TransactionType.TRANSFER, 1L, 2L, new BigDecimal("100.0000"))).isEqualTo(plain);
        assertThat(RequestFingerprint.of(TransactionType.TRANSFER, 1L, 2L, new BigDecimal("1E+2"))).isEqualTo(plain);
    }

    @Test
    void anyBusinessDifference_changesTheHash() {
        String original = RequestFingerprint.of(TransactionType.TRANSFER, 1L, 2L, new BigDecimal("100"));

        assertThat(RequestFingerprint.of(TransactionType.TRANSFER, 1L, 2L, new BigDecimal("100.0001"))).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(TransactionType.TRANSFER, 2L, 1L, new BigDecimal("100"))).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(TransactionType.DEPOSIT, 1L, 2L, new BigDecimal("100"))).isNotEqualTo(original);
    }

    @Test
    void accountIdsCannotBeShiftedBetweenFields() {
        // "1|23" and "12|3" must not collide: the separator keeps the fields apart
        assertThat(RequestFingerprint.of(TransactionType.TRANSFER, 1L, 23L, BigDecimal.ONE))
                .isNotEqualTo(RequestFingerprint.of(TransactionType.TRANSFER, 12L, 3L, BigDecimal.ONE));
    }

    @Test
    void hashIsHexSha256() {
        assertThat(RequestFingerprint.of(TransactionType.DEPOSIT, null, 5L, BigDecimal.TEN))
                .hasSize(64)
                .matches("[0-9a-f]+");
    }
}
