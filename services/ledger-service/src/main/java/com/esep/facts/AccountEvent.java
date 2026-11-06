package com.esep.facts;
import java.time.Instant;
import java.util.UUID;
public record AccountEvent(int schemaVersion,String eventType,String producer,UUID eventId,long accountId,
        long ownerUserId,String accountType,String currency,String status,long version,Instant createdAt,Instant closedAt) {
    public static final String TYPE="ACCOUNT_STATE_CHANGED";
}
