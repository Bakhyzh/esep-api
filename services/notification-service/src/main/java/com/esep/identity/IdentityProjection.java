package com.esep.identity;

import com.esep.security.KeycloakProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Dedicated projection role/transaction, before and separate from money transactions. */
@Service
@ConditionalOnProperty(name="esep.identity.enabled",havingValue="true")
public class IdentityProjection {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper mapper;
    private final String issuer;
    private HikariDataSource pool;
    public IdentityProjection(JdbcTemplate financial,TransactionTemplate financialTx,JsonMapper mapper,KeycloakProperties oidc,
            Environment environment,@Value("${esep.identity.jdbc-url:}") String url,
            @Value("${esep.identity.username:}") String username,@Value("${esep.identity.password:}") String password) {
        this.mapper=mapper;this.issuer=oidc.issuerUri();
        if(url.isBlank()) {
            if(!environment.acceptsProfiles(Profiles.of("test"))) throw new IllegalStateException("Projection database role must be configured");
            jdbc=financial;tx=financialTx;
        } else {
            var c=new HikariConfig();c.setJdbcUrl(url);c.setUsername(username);c.setPassword(password);
            c.setMaximumPoolSize(2);c.setMinimumIdle(0);c.setPoolName("identity-projection");
            pool=new HikariDataSource(c);jdbc=new JdbcTemplate(pool);tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
        }
    }
    @PreDestroy public void close() { if(pool!=null)pool.close(); }
    public void apply(IdentityEvent event) {
        validate(event);
        String hash=hash(mapper.writeValueAsString(event));
        tx.executeWithoutResult(s->{
            int inserted=jdbc.update("INSERT INTO identity_processed_events(event_id,payload_hash) VALUES (?,?) ON CONFLICT DO NOTHING",event.eventId(),hash);
            if(inserted==0) {
                if(!hash.equals(jdbc.queryForObject("SELECT payload_hash FROM identity_processed_events WHERE event_id=?",String.class,event.eventId())))
                    throw new IllegalArgumentException("Conflicting event ID payload");
                return;
            }
            jdbc.query("SELECT pg_advisory_xact_lock(?)",rs->{},event.userId());
            var state=jdbc.queryForList("SELECT * FROM identity_projection_state WHERE user_id=? FOR UPDATE",event.userId());
            if(state.isEmpty()) {
                if(!jdbc.queryForList("SELECT id FROM identity_principals WHERE id=?",Long.class,event.userId()).isEmpty())
                    throw new IllegalArgumentException("Existing principal requires approved migration");
                jdbc.update("INSERT INTO identity_principals(id,kind) VALUES (?,'HUMAN')",event.userId());
                jdbc.update("INSERT INTO identity_bindings(user_id,issuer,subject,linked_by) VALUES (?,?,?,'user-authority-event:v1')",
                        event.userId(),event.issuer(),event.subject());
                jdbc.update("INSERT INTO identity_projection_state(user_id,issuer,subject,source_version,status) VALUES (?,?,?,?,?)",
                        event.userId(),event.issuer(),event.subject(),event.version(),event.status());
            } else {
                var old=state.getFirst();
                if(!event.issuer().equals(old.get("issuer")) || !event.subject().equals(old.get("subject")))
                    throw new IllegalArgumentException("Immutable identity conflict");
                if(event.version()<((Number)old.get("source_version")).longValue())return;
                if(event.version()==((Number)old.get("source_version")).longValue()) {
                    if(!event.status().equals(old.get("status")))throw new IllegalArgumentException("Conflicting source version");
                    return;
                }
                jdbc.update("UPDATE identity_projection_state SET source_version=?,status=?,updated_at=now() WHERE user_id=?",
                        event.version(),event.status(),event.userId());
            }
        });
    }
    private void validate(IdentityEvent e) {
        if(e==null || e.schemaVersion()!=1 || !"USER_IDENTITY_CHANGED".equals(e.eventType()) || e.eventId()==null
                || e.userId()<=0 || e.userId()==Long.MAX_VALUE || !issuer.equals(e.issuer()) || e.subject()==null
                || e.subject().isBlank() || e.subject().length()>255 || e.version()<1 || e.occurredAt()==null
                || !("ACTIVE".equals(e.status()) || "DISABLED".equals(e.status()))) throw new IllegalArgumentException("Invalid identity contract");
    }
    private static String hash(String payload) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
