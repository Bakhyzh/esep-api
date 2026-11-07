package com.esep.observability;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.*;
import java.util.*;
@Configuration(proxyBeanMethods=false)
public class MetricPrivacy {
    @Bean MeterFilter safeMetricTags(){return new MeterFilter(){
        @Override public Meter.Id map(Meter.Id id){
            var tags=new ArrayList<Tag>();
            for(Tag tag:id.getTags()){
                if(Set.of("user","username","user_id","account_id","subject","email","authorization","url","path","principal","security.authentication.name").contains(tag.getKey()))continue;
                String value=tag.getValue();
                if(tag.getKey().equals("uri")){
                    try{if(value.startsWith("http"))value=java.net.URI.create(value).getPath();}catch(RuntimeException error){value="unknown";}
                    value=value.replaceAll("[?].*$","").replaceAll("/[0-9]+(?=/|$)","/{id}")
                        .replaceAll("/[0-9a-fA-F-]{36}(?=/|$)","/{id}");
                    if(!Set.of("/api/auth/me", "/api/users/me", "/api/users/{id}/enable", "/api/users/{id}/disable", "/api/accounts", "/api/accounts/{id}", "/api/accounts/{id}/close", "/api/transfers", "/api/deposits", "/api/transactions", "/api/transactions/{id}", "/api/notifications", "/api/notifications/unread-count", "/api/notifications/{id}/read", "/api/analytics/spending", "/api/analytics/top-transactions", "/api/analytics/moving-average", "/api/analytics/monthly-comparison", "/internal/identity/snapshots", "/internal/identity/replay", "/internal/ledger/write-fence", "/internal/ledger/postings-export", "/internal/ledger/accounts-export", "/internal/ledger/reconciliation", "/internal/analytics/backfill", "/internal/analytics/reconciliation", "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness", "/actuator/prometheus", "UNKNOWN", "NOT_FOUND", "REDIRECTION", "root", "none").contains(value))value="other";
                }
                tags.add(Tag.of(tag.getKey(),value));
            }
            return id.replaceTags(tags);
        }
    };}
}
