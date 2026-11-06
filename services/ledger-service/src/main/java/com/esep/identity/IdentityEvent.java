package com.esep.identity;
import java.time.Instant;
import java.util.UUID;
public record IdentityEvent(int schemaVersion,String eventType,UUID eventId,long userId,
        String issuer,String subject,long version,String status,Instant occurredAt) {}
