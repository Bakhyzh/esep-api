package com.esep.notification;

import com.esep.notification.dto.NotificationResponse;
import com.esep.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Notifications", description = "Transfer notifications delivered asynchronously via Kafka (outbox -> topic -> consumer)")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "Latest notifications of the current user (eventually consistent: may lag a second behind a transfer)")
    @GetMapping
    public List<NotificationResponse> latest(@AuthenticationPrincipal CurrentUser currentUser,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return notificationService.latest(currentUser.id(), limit);
    }
}
