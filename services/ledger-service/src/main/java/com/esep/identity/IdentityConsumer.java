package com.esep.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="esep.identity.enabled",havingValue="true")
public class IdentityConsumer {
    private final JsonMapper mapper;
    private final IdentityProjection projection;
    @KafkaListener(topics="${esep.identity.topic:esep.users.v1}",groupId="${esep.identity.group:esep-ledger-identities-v1}")
    public void consume(String json) {
        try {projection.apply(mapper.readValue(json,IdentityEvent.class));}
        catch(tools.jackson.core.JacksonException e) {throw new IllegalArgumentException("Malformed identity event",e);}
    }
}
