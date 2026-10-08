package com.esep.notification;

import com.esep.notification.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    /** @return false if this consumer has already processed the event (duplicate delivery). */
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.update("""
                INSERT INTO processed_events (consumer, event_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, consumer, eventId) == 1;
    }

    public void insert(long userId, UUID eventId, NotificationType type, String message) {
        jdbc.update("INSERT INTO notifications (user_id, event_id, type, message) VALUES (?, ?, ?, ?)",
                userId, eventId, type.name(), message);
    }

    public List<NotificationResponse> findLatest(long userId, int limit) {
        return jdbc.query("""
                SELECT id, type, message, created_at FROM notifications
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, (rs, i) -> new NotificationResponse(
                rs.getLong("id"),
                NotificationType.valueOf(rs.getString("type")),
                rs.getString("message"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), userId, limit);
    }
}
