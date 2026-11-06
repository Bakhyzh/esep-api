package com.esep.projection;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AnalyticsProjection {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    @Transactional public void postings(PostingEvent e){
        validate(e);
        if(!processed(e.eventId(),mapper.writeValueAsString(e)))return;
        String semantic=hash(e.transactionType()+"|"+e.occurredAt()+"|"+mapper.writeValueAsString(e.postings()));
        int tx=jdbc.update("INSERT INTO analytics_transactions(id,type,created_at,semantic_hash) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
            e.transactionId(),e.transactionType(),java.sql.Timestamp.from(e.occurredAt()),semantic);
        if(tx==0 && !semantic.equals(jdbc.queryForObject("SELECT semantic_hash FROM analytics_transactions WHERE id=?",String.class,e.transactionId())))
            throw new IllegalArgumentException("Conflicting immutable transaction");
        Set<Long> changed=new HashSet<>();
        for(var p:e.postings()){
            account(p.accountId(),p.ownerUserId(),p.accountType(),p.currency(),p.createdAt());
            String itemHash=hash(e.transactionId()+"|"+mapper.writeValueAsString(p));
            int added=jdbc.update("INSERT INTO analytics_entries(id,transaction_id,account_id,direction,amount,created_at,semantic_hash) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                p.entryId(),e.transactionId(),p.accountId(),p.direction(),new BigDecimal(p.amount()),java.sql.Timestamp.from(p.createdAt()),itemHash);
            if(added==0 && !itemHash.equals(jdbc.queryForObject("SELECT semantic_hash FROM analytics_entries WHERE id=?",String.class,p.entryId())))
                throw new IllegalArgumentException("Conflicting immutable posting");
            if(added>0 && "DEBIT".equals(p.direction()))changed.add(p.ownerUserId());
        }
        changed.forEach(id->jdbc.update("INSERT INTO analytics_generations(user_id,generation) VALUES (?,1) ON CONFLICT(user_id) DO UPDATE SET generation=analytics_generations.generation+1,updated_at=now()",id));
    }
    @Transactional public void account(AccountEvent e){
        if(e==null || e.schemaVersion()!=1 || !AccountEvent.TYPE.equals(e.eventType()) || !"ledger-service".equals(e.producer())
            || e.eventId()==null || e.accountId()<1 || e.ownerUserId()<1 || e.createdAt()==null || e.version()<0
            || !("ACTIVE".equals(e.status()) || "CLOSED".equals(e.status()))
            || ("CLOSED".equals(e.status()) != (e.closedAt()!=null)))throw new IllegalArgumentException("Invalid account contract");
        if(!processed(e.eventId(),mapper.writeValueAsString(e)))return;
        account(e.accountId(),e.ownerUserId(),e.accountType(),e.currency(),e.createdAt());
        var old=jdbc.queryForMap("SELECT entity_version,status,created_at,closed_at FROM analytics_accounts WHERE id=? FOR UPDATE",e.accountId());
        long version=((Number)old.get("entity_version")).longValue();
        if(e.version()<version)return;
        if(version>=0 && !e.createdAt().equals(((java.sql.Timestamp)old.get("created_at")).toInstant()))
            throw new IllegalArgumentException("Immutable account creation conflict");
        var oldClosed=old.get("closed_at")==null?null:((java.sql.Timestamp)old.get("closed_at")).toInstant();
        if(e.version()==version && (!e.status().equals(old.get("status")) || !Objects.equals(e.closedAt(),oldClosed)))
            throw new IllegalArgumentException("Conflicting account version");
        jdbc.update("UPDATE analytics_accounts SET status=?,entity_version=?,created_at=?,closed_at=? WHERE id=?",e.status(),e.version(),
            java.sql.Timestamp.from(e.createdAt()),e.closedAt()==null?null:java.sql.Timestamp.from(e.closedAt()),e.accountId());
    }
    private void account(long id,long owner,String type,String currency,java.time.Instant created){
        if(!("USER".equals(type)||"SYSTEM".equals(type)) || currency==null || !currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid account metadata");
        jdbc.update("INSERT INTO analytics_accounts(id,user_id,type,currency,created_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",id,owner,type,currency,java.sql.Timestamp.from(created));
        var row=jdbc.queryForMap("SELECT user_id,type,currency FROM analytics_accounts WHERE id=?",id);
        if(((Number)row.get("user_id")).longValue()!=owner || !type.equals(row.get("type")) || !currency.equals(row.get("currency")))
            throw new IllegalArgumentException("Immutable account ownership conflict");
    }
    private boolean processed(UUID id,String value){
        String hash=hash(value);int added=jdbc.update("INSERT INTO processed_events(event_id,payload_hash) VALUES (?,?) ON CONFLICT DO NOTHING",id,hash);
        if(added==0 && !hash.equals(jdbc.queryForObject("SELECT payload_hash FROM processed_events WHERE event_id=?",String.class,id)))
            throw new IllegalArgumentException("Conflicting event ID");
        return added==1;
    }
    private void validate(PostingEvent e){
        if(e==null || e.schemaVersion()!=1 || !PostingEvent.TYPE.equals(e.eventType()) || !"ledger-service".equals(e.producer()) || e.eventId()==null
            || e.transactionId()<1 || e.occurredAt()==null || e.postings()==null || e.postings().size()!=2
            || e.transactionType()==null || !Set.of("TRANSFER","DEPOSIT","WITHDRAWAL").contains(e.transactionType()))throw new IllegalArgumentException("Invalid posting contract");
        BigDecimal sum=BigDecimal.ZERO;Set<String> currency=new HashSet<>();Set<Long> ids=new HashSet<>();Set<String> directions=new HashSet<>();
        for(var p:e.postings()){
            if(p==null || p.entryId()<1 || p.accountId()<1 || p.ownerUserId()<1 || p.createdAt()==null || p.amount()==null || !ids.add(p.entryId())
                || !("DEBIT".equals(p.direction()) || "CREDIT".equals(p.direction())))throw new IllegalArgumentException("Invalid posting");
            BigDecimal amount;
            try{amount=new BigDecimal(p.amount()).setScale(4,RoundingMode.UNNECESSARY);}catch(RuntimeException ex){throw new IllegalArgumentException("Invalid exact decimal",ex);}
            if(amount.signum()<=0 || amount.precision()>19)throw new IllegalArgumentException("Invalid positive amount");
            sum=sum.add("DEBIT".equals(p.direction())?amount.negate():amount);currency.add(p.currency());directions.add(p.direction());
        }
        if(sum.signum()!=0 || currency.size()!=1 || directions.size()!=2)throw new IllegalArgumentException("Unbalanced financial fact");
    }
    static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
