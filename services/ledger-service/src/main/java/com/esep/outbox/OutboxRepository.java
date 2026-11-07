package com.esep.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Plain JDBC: FOR UPDATE SKIP LOCKED and batch updates are clearer in SQL than in JPA. */
@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    private final JdbcTemplate jdbc;
    private final com.esep.observability.TraceSupport trace;

    /** Must be called inside the business transaction: that is the whole point of the outbox. */
    public void append(UUID eventId, String aggregateType, long aggregateId, String eventType,
                       String messageKey, String payloadJson) {
        append(eventId,aggregateType,aggregateId,eventType,messageKey,payloadJson,null);
    }
    public void append(UUID eventId,String aggregateType,long aggregateId,String eventType,String messageKey,String payloadJson,String targetTopic) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, message_key, payload, target_topic, trace_parent)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, eventId, aggregateType, aggregateId, eventType, messageKey, payloadJson, targetTopic, trace.capture());
    }

    /**
     * Locks the oldest unpublished events. SKIP LOCKED lets several app instances publish
     * in parallel: each takes rows the others have not locked, nobody waits.
     */
    public List<OutboxEvent> lockNextBatch(int limit) {
        return jdbc.query("""
                SELECT id, event_id, event_type, message_key, payload::text AS payload, target_topic, trace_parent
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new OutboxEvent(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("message_key"),
                rs.getString("payload"),rs.getString("target_topic"),rs.getString("trace_parent")), limit);
    }

    public void markPublished(List<Long> ids) {
        jdbc.batchUpdate("UPDATE outbox_events SET published_at = now(), last_error = NULL WHERE id = ?",
                ids, ids.size(), (ps, id) -> ps.setLong(1, id));
    }

    public void markFailed(long id, String error) {
        jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = left(?, 1000) WHERE id = ?",
                error, id);
    }

    public record OutboxEvent(long id, UUID eventId, String eventType, String messageKey, String payload,String targetTopic,String traceParent) {
        public OutboxEvent(long id,UUID eventId,String eventType,String messageKey,String payload){this(id,eventId,eventType,messageKey,payload,null,null);}
    }
}
