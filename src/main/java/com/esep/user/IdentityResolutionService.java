package com.esep.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IdentityResolutionService {
    private final UserIdentityRepository identities;

    /** Exact validated issuer/sub only. No email lookup, creation, or linking side effects. */
    public Optional<Long> resolve(String issuer, String subject) {
        return identities.findByIssuerAndSubject(issuer, subject)
                .map(UserIdentity::getUser)
                .filter(user -> !"system@esep.internal".equals(user.getEmail()))
                .map(User::getId);
    }
}
