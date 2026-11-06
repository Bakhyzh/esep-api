package com.esep.facts;

import com.esep.outbox.OutboxRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@RequiredArgsConstructor
public class LedgerFactWriter {
    public static final String POSTINGS_TOPIC="esep.ledger.postings.v1";
    public static final String ACCOUNTS_TOPIC="esep.ledger.accounts.v1";
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    private final JsonMapper mapper;
    @Transactional(propagation=Propagation.MANDATORY)
    public void posted(long id){
        entities.flush(); // Read authoritative PostgreSQL decimal/timestamp precision, not unflushed JVM values.
        var event=transactionSnapshot(id,UUID.randomUUID());
        outbox.append(event.eventId(),"transaction",id,PostingEvent.TYPE,String.valueOf(id),mapper.writeValueAsString(event),POSTINGS_TOPIC);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void accountChanged(long id){
        entities.flush();var event=accountSnapshot(id,UUID.randomUUID());
        outbox.append(event.eventId(),"account",id,AccountEvent.TYPE,String.valueOf(id),mapper.writeValueAsString(event),ACCOUNTS_TOPIC);
    }
    public PostingEvent transactionSnapshot(long id,UUID eventId){
        var tx=jdbc.queryForMap("SELECT type,created_at FROM transactions WHERE id=?",id);
        var entries=jdbc.query("""
          SELECT e.id,e.account_id,a.user_id,a.type,a.currency,e.direction,e.amount,e.created_at
          FROM ledger_entries e JOIN accounts a ON a.id=e.account_id WHERE e.transaction_id=? ORDER BY e.id
          """,(r,n)->new PostingEvent.Posting(r.getLong("id"),r.getLong("account_id"),r.getLong("user_id"),r.getString("type"),
                r.getString("currency"),r.getString("direction"),r.getBigDecimal("amount").toPlainString(),r.getTimestamp("created_at").toInstant()),id);
        return new PostingEvent(1,PostingEvent.TYPE,"ledger-service",eventId,id,(String)tx.get("type"),
            ((java.sql.Timestamp)tx.get("created_at")).toInstant(),entries);
    }
    public AccountEvent accountSnapshot(long id,UUID eventId){
        return jdbc.queryForObject("SELECT * FROM accounts WHERE id=?",(r,n)->new AccountEvent(1,AccountEvent.TYPE,"ledger-service",eventId,id,
            r.getLong("user_id"),r.getString("type"),r.getString("currency"),r.getString("status"),r.getLong("version"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("closed_at")==null?null:r.getTimestamp("closed_at").toInstant()),id);
    }
    public static UUID backfillId(String kind,long id,long version){
        return UUID.nameUUIDFromBytes(("ledger:backfill:v1:"+kind+":"+id+":"+version).getBytes(StandardCharsets.UTF_8));
    }
}
