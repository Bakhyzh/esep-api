package com.esep.notification;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
@Component
@RequiredArgsConstructor
public class NotificationListener {
    private final JsonMapper mapper;
    private final NotificationProcessor processor;
    @KafkaListener(topics={"${esep.notification.transfers-topic}","${esep.notification.replay-topic}"},groupId="esep-notifications-v1")
    public void consume(String json){
        try{processor.apply(mapper.readValue(json,TransferCompletedEvent.class));}
        catch(tools.jackson.core.JacksonException e){throw new IllegalArgumentException("Malformed financial event",e);}
    }
}
