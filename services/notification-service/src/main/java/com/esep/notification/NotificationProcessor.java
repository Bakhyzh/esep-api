package com.esep.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NotificationProcessor {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    @Transactional public void apply(TransferCompletedEvent event) {
        validate(event);String hash=hash(mapper.writeValueAsString(event));
        if(jdbc.update("INSERT INTO processed_events(event_id,payload_hash) VALUES (?,?) ON CONFLICT DO NOTHING",event.eventId(),hash)==0) {
            if(!hash.equals(jdbc.queryForObject("SELECT payload_hash FROM processed_events WHERE event_id=?",String.class,event.eventId())))
                throw new IllegalArgumentException("Conflicting notification event ID");
            return;
        }
        String amount=event.amount().stripTrailingZeros().toPlainString()+" "+event.currency();
        insert(event.fromUserId(),event.eventId(),"TRANSFER_SENT","You sent "+amount+" from account "+event.fromAccountId()+" to account "+event.toAccountId());
        if(!event.fromUserId().equals(event.toUserId()))insert(event.toUserId(),event.eventId(),"TRANSFER_RECEIVED","You received "+amount+" on account "+event.toAccountId());
    }
    private void insert(long user,UUID event,String type,String message) {
        jdbc.update("INSERT INTO notifications(user_id,event_id,type,message) VALUES (?,?,?,?)",user,event,type,message);
    }
    private void validate(TransferCompletedEvent e) {
        if(e==null || e.schemaVersion()!=1 || !TransferCompletedEvent.TYPE.equals(e.eventType()) || e.eventId()==null
            || e.transactionId()==null || e.transactionId()<=0 || e.fromUserId()==null || e.fromUserId()<=0
            || e.toUserId()==null || e.toUserId()<=0 || e.fromAccountId()==null || e.fromAccountId()<=0
            || e.toAccountId()==null || e.toAccountId()<=0 || e.occurredAt()==null || e.amount()==null
            || e.amount().signum()<=0 || e.amount().stripTrailingZeros().scale()>4 || e.currency()==null || !e.currency().matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Invalid transfer notification contract");
    }
    static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
