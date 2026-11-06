package com.esep.profile;
import java.time.Instant;
import java.util.UUID;
/** Full immutable identity snapshot, no credential or role claims. */
public record IdentityEvent(int schemaVersion,String eventType,UUID eventId,long userId,
        String issuer,String subject,long version,String status,Instant occurredAt) {
    public static final String TYPE="USER_IDENTITY_CHANGED";
}
