package com.esep.notification;

import com.esep.common.exception.ResourceNotFoundException;
import com.esep.security.CurrentUser;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final JdbcTemplate jdbc;
    @GetMapping public List<Notification> latest(@AuthenticationPrincipal CurrentUser user,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){
        return jdbc.query("SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
            (r,n)->new Notification(r.getLong("id"),r.getString("type"),r.getString("message"),r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("read_at")==null?null:r.getTimestamp("read_at").toInstant()),user.id(),limit);
    }
    @GetMapping("/unread-count") public Map<String,Long> unread(@AuthenticationPrincipal CurrentUser user){
        return Map.of("count",jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL",Long.class,user.id()));
    }
    @PatchMapping("/{id}/read") @Transactional public Map<String,Object> read(@AuthenticationPrincipal CurrentUser user,@PathVariable long id){
        if(jdbc.update("UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE id=? AND user_id=?",id,user.id())!=1)
            throw new ResourceNotFoundException("Notification",id);
        return Map.of("id",id,"readAt",jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=?",java.sql.Timestamp.class,id).toInstant());
    }
    public record Notification(long id,String type,String message,Instant createdAt,Instant readAt){}
}
