package com.esep.identity;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.*;
import java.util.*;
@RestController
@Hidden
@RequiredArgsConstructor
public class IdentityAuditController {
    private final JdbcTemplate jdbc;
    @GetMapping("/internal/identity/snapshots") public List<Map<String,Object>> snapshots(
            @RequestParam(defaultValue="0") @Min(0) long after,@RequestParam(defaultValue="100") @Min(1) @Max(1000) int limit) {
        return jdbc.queryForList("SELECT user_id,issuer,subject,source_version,status FROM identity_projection_state WHERE user_id>? ORDER BY user_id LIMIT ?",after,limit);
    }
}
