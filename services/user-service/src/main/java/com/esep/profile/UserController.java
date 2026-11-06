package com.esep.profile;
import com.esep.security.SecurityConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserAuthority users;
    @GetMapping({"/api/auth/me","/api/users/me"})
    public Map<String,Object> me(@AuthenticationPrincipal Jwt jwt) {
        var p=users.current(jwt);
        return Map.of("id",p.id(),"email",p.email(),"displayName",p.displayName(),"status",p.status(),"version",p.version(),
                "createdAt",p.createdAt(),"role",SecurityConfig.roles(jwt).stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"))?"ADMIN":"USER");
    }
    @PatchMapping("/api/users/me") public UserAuthority.Profile update(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Update request) {
        return users.update(jwt,request.displayName(),request.version());
    }
    @PostMapping("/api/users/{id}/disable") public void disable(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { users.status(jwt,id,"DISABLED"); }
    @PostMapping("/api/users/{id}/enable") public void enable(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { users.status(jwt,id,"ACTIVE"); }
    @GetMapping("/internal/identity/snapshots") public List<IdentityEvent> snapshots(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") @Min(0) long after,@RequestParam(defaultValue="100") @Min(1) @Max(1000) int limit) {
        users.current(jwt);return users.snapshots(jwt,after,limit);
    }
    @PostMapping("/internal/identity/replay") public Map<String,Integer> replay(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") @Min(0) long after,@RequestParam(defaultValue="100") @Min(1) @Max(1000) int limit) {
        return Map.of("enqueued",users.replay(jwt,after,limit));
    }
    public record Update(@NotNull @Size(max=100) String displayName,@Min(1) long version) {}
}
