package com.esep.principal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Local immutable business-ID reference, not a user profile or ID allocator. */
@Entity
@Table(name = "ledger_principals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerPrincipal {
    @Id
    private Long id;
    @Column(nullable = false, updatable = false, length = 10)
    private String kind;
    public LedgerPrincipal(Long approvedUserId) {
        this.id = approvedUserId;
        this.kind = "HUMAN";
    }
}
