package com.esep.user;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/** Only for a validated, permitted-role token. Never attaches an existing email owner. */
@Service
@RequiredArgsConstructor
public class IdentityProvisioningService {
    private final JdbcTemplate jdbc;

    @Transactional
    public Optional<Long> provision(String issuer, String subject, String verifiedEmail) {
        if (verifiedEmail == null || verifiedEmail.isBlank() || verifiedEmail.length() > 255
                || !verifiedEmail.contains("@")) {
            return Optional.empty();
        }
        String email = verifiedEmail.trim().toLowerCase(Locale.ROOT);
        if (email.endsWith("@esep.internal")) {
            return Optional.empty();
        }
        // Transaction-scoped lock works across application instances. After waiting, the next
        // READ COMMITTED statement sees the first login's committed mapping. Hash collisions
        // only serialize unrelated identities; they cannot link them.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, issuer + "\n" + subject);
        var existing = jdbc.queryForList("""
                SELECT i.user_id FROM user_identities i JOIN users u ON u.id=i.user_id
                WHERE i.issuer=? AND i.subject=? AND u.email <> 'system@esep.internal'
                """, Long.class, issuer, subject);
        if (!existing.isEmpty()) {
            return Optional.of(existing.getFirst());
        }
        // Unique email arbitrates different subjects racing with the same email. A collision
        // requires explicit operator migration, even if the JWT says email_verified=true.
        var created = jdbc.queryForList("""
                INSERT INTO users(email,password_hash,role) VALUES (?,NULL,'USER')
                ON CONFLICT (email) DO NOTHING RETURNING id
                """, Long.class, email);
        if (created.isEmpty()) {
            return Optional.empty();
        }
        Long id = created.getFirst();
        jdbc.update("""
                INSERT INTO user_identities(user_id,issuer,subject,linked_by)
                VALUES (?,?,?,'oidc-first-login:v1')
                """, id, issuer, subject);
        return Optional.of(id);
    }
}
