package com.esep.principal;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** Read-only approved projection; no email, userId claim, remote DB or network lookup. */
@Service
@RequiredArgsConstructor
public class IdentityResolutionService {
    private final JdbcTemplate jdbc;
    @Transactional(readOnly = true)
    public Optional<Long> resolve(String issuer, String subject) {
        return jdbc.queryForList("""
                SELECT i.user_id FROM ledger_identity_bindings i
                JOIN ledger_principals p ON p.id=i.user_id
                WHERE i.issuer=? AND i.subject=? AND p.kind='HUMAN'
                """, Long.class, issuer, subject).stream().findFirst();
    }
}
