package com.esep.profile;

import com.esep.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UserAuthority {
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    private final JsonMapper mapper;

    @Transactional
    public Profile current(Jwt jwt) {
        String issuer=jwt.getIssuer().toString(),subject=jwt.getSubject();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},issuer+"\n"+subject);
        var found=lookup(issuer,subject);
        if(!found.isEmpty()) return active(found.getFirst());
        String email=jwt.getClaimAsString("email");
        if(!Boolean.TRUE.equals(jwt.getClaim("email_verified")) || email==null || email.isBlank() || email.length()>255)
            throw new AccessDeniedException("Verified activation required");
        email=email.trim().toLowerCase(Locale.ROOT);
        if(!email.contains("@") || email.endsWith("@esep.internal")) throw new AccessDeniedException("Invalid human identity");
        var ids=jdbc.queryForList("INSERT INTO users(email) VALUES (?) ON CONFLICT(email) DO NOTHING RETURNING id",Long.class,email);
        if(ids.isEmpty()) throw new AccessDeniedException("Existing profile requires approved identity migration");
        long id=ids.getFirst();
        jdbc.update("INSERT INTO user_identities(user_id,issuer,subject,linked_by) VALUES (?,?,?,'verified-oidc-provisioning:v1')",id,issuer,subject);
        emit(id);
        return lookup(issuer,subject).getFirst();
    }
    @Transactional
    public Profile update(Jwt jwt,String name,long expectedVersion) {
        Profile p=current(jwt);
        if(jdbc.update("UPDATE users SET display_name=?,version=version+1 WHERE id=? AND version=?",name,p.id(),expectedVersion)!=1)
            throw new com.esep.common.exception.ConflictException(com.esep.common.exception.ErrorCode.CONFLICT,"Profile changed; reload before updating");
        emit(p.id());return lookup(jwt.getIssuer().toString(),jwt.getSubject()).getFirst();
    }
    @Transactional
    public void status(Jwt actor,long target,String status) {
        current(actor);
        var locked=jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,target);
        if(locked.isEmpty()) throw new com.esep.common.exception.ResourceNotFoundException("User",target);
        if(jdbc.update("UPDATE users SET status=?,version=version+1 WHERE id=? AND status<>?",status,target,status)>0) emit(target);
    }
    @Transactional(readOnly=true)
    public List<IdentityEvent> snapshots(Jwt actor,long after,int limit) {
        // The controller resolves the actor in a separate write-capable transaction first.
        return jdbc.query("""
            SELECT u.id,u.version,u.status,i.issuer,i.subject FROM users u
            JOIN user_identities i ON i.user_id=u.id WHERE u.id>? ORDER BY u.id LIMIT ?
            """,(r,n)->new IdentityEvent(1,IdentityEvent.TYPE,UUID.randomUUID(),r.getLong("id"),r.getString("issuer"),
                r.getString("subject"),r.getLong("version"),r.getString("status"),Instant.now()),after,limit);
    }
    @Transactional
    public int replay(Jwt actor,long after,int limit) {
        current(actor);
        var ids=jdbc.queryForList("SELECT id FROM users WHERE id>? ORDER BY id LIMIT ?",Long.class,after,limit);
        ids.forEach(this::emit);return ids.size();
    }
    private void emit(long id) {
        jdbc.query("SELECT u.version,u.status,i.issuer,i.subject FROM users u JOIN user_identities i ON i.user_id=u.id WHERE u.id=?",
            (org.springframework.jdbc.core.RowCallbackHandler) r->{
                var event=new IdentityEvent(1,IdentityEvent.TYPE,UUID.randomUUID(),id,r.getString("issuer"),r.getString("subject"),
                    r.getLong("version"),r.getString("status"),Instant.now());
                outbox.append(event.eventId(),"user",id,IdentityEvent.TYPE,String.valueOf(id),mapper.writeValueAsString(event));
            },id);
    }
    private List<Profile> lookup(String issuer,String subject) {
        return jdbc.query("SELECT u.* FROM users u JOIN user_identities i ON i.user_id=u.id WHERE i.issuer=? AND i.subject=?",
            (r,n)->new Profile(r.getLong("id"),r.getString("email"),r.getString("display_name"),r.getString("status"),
                r.getLong("version"),r.getTimestamp("created_at").toInstant()),issuer,subject);
    }
    private Profile active(Profile p) {
        if(!"ACTIVE".equals(p.status())) throw new AccessDeniedException("Identity disabled");return p;
    }
    public record Profile(long id,String email,String displayName,String status,long version,Instant createdAt) {}
}
