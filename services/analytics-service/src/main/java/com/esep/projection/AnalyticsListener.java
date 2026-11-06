package com.esep.projection;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
@Component
@RequiredArgsConstructor
public class AnalyticsListener {
    private final JsonMapper mapper;private final AnalyticsProjection projection;
    @KafkaListener(topics="esep.ledger.postings.v1",groupId="esep-analytics-postings-v1") public void postings(String json){
        try{projection.postings(mapper.readValue(json,PostingEvent.class));}catch(tools.jackson.core.JacksonException e){throw new IllegalArgumentException("Malformed posting",e);}
    }
    @KafkaListener(topics="esep.ledger.accounts.v1",groupId="esep-analytics-accounts-v1") public void accounts(String json){
        try{projection.account(mapper.readValue(json,AccountEvent.class));}catch(tools.jackson.core.JacksonException e){throw new IllegalArgumentException("Malformed account",e);}
    }
}
