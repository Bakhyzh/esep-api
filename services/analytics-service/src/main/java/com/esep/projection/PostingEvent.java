package com.esep.projection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record PostingEvent(int schemaVersion,String eventType,String producer,UUID eventId,long transactionId,
        String transactionType,Instant occurredAt,List<Posting> postings) {
    public static final String TYPE="LEDGER_TRANSACTION_POSTED";
    public record Posting(long entryId,long accountId,long ownerUserId,String accountType,String currency,
            String direction,String amount,Instant createdAt){}
}
