package com.esep.transaction;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable SHA-256 of the business meaning of a request, used to detect Idempotency-Key reuse. */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(TransactionType type, Long fromAccountId, Long toAccountId, BigDecimal amount) {
        // stripTrailingZeros: 100, 100.0 and 100.0000 are the same money and must give the same hash
        String canonical = String.join("|",
                type.name(),
                String.valueOf(fromAccountId),
                String.valueOf(toAccountId),
                amount.stripTrailingZeros().toPlainString());
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
