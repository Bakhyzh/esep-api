package com.esep.notification.dto;

import com.esep.notification.NotificationType;

import java.time.Instant;

public record NotificationResponse(Long id, NotificationType type, String message, Instant createdAt) {
}
