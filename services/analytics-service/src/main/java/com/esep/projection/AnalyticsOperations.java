package com.esep.projection;

import com.esep.security.CurrentUserAuthentication;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.core.ParameterizedTypeReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** Explicit operator operation. The source must already be fenced; no client-supplied facts or URLs. */
@RestController
@Hidden
@RequestMapping("/internal/analytics")
public class AnalyticsOperations {
    private final AnalyticsProjection projection;
    private final JdbcTemplate jdbc;
    private final RestClient ledger;
    public AnalyticsOperations(AnalyticsProjection projection,JdbcTemplate jdbc,@Value("${esep.analytics.ledger-base-url}") URI base){
        if(!Set.of("http","https").contains(base.getScheme()) || base.getUserInfo()!=null || base.getHost()==null)
            throw new IllegalArgumentException("Invalid configured Ledger address");
        this.projection=projection;this.jdbc=jdbc;
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(client);factory.setReadTimeout(Duration.ofSeconds(30));
        this.ledger=RestClient.builder().baseUrl(base.toString()).requestFactory(factory).build();
    }
    @PostMapping("/backfill") public Map<String,Long> backfill(@RequestParam long epoch,CurrentUserAuthentication authentication){
        if(epoch<1)throw new IllegalArgumentException("A fenced epoch is required");
        String token=authentication.getCredentials().getTokenValue();
        long accounts=0,postings=0,after=0;
        while(true){
            final long cursor=after;
            var batch=ledger.get().uri(b->b.path("/internal/ledger/accounts-export").queryParam("epoch",epoch).queryParam("after",cursor).build())
                .headers(h->h.setBearerAuth(token)).retrieve().body(new ParameterizedTypeReference<List<AccountEvent>>(){});
            if(batch==null || batch.isEmpty())break;
            for(var e:batch){projection.account(e);after=e.accountId();accounts++;}
        }
        after=0;
        while(true){
            final long cursor=after;
            var batch=ledger.get().uri(b->b.path("/internal/ledger/postings-export").queryParam("epoch",epoch).queryParam("after",cursor).build())
                .headers(h->h.setBearerAuth(token)).retrieve().body(new ParameterizedTypeReference<List<PostingEvent>>(){});
            if(batch==null || batch.isEmpty())break;
            for(var e:batch){projection.postings(e);after=e.transactionId();postings++;}
        }
        // Final epoch check detects resumed or refenced source even when the last page was empty.
        ledger.get().uri(b->b.path("/internal/ledger/accounts-export").queryParam("epoch",epoch).queryParam("after",Long.MAX_VALUE).build())
            .headers(h->h.setBearerAuth(token)).retrieve().toBodilessEntity();
        return Map.of("accounts",accounts,"transactions",postings,"epoch",epoch);
    }
    @GetMapping("/reconciliation") public List<Map<String,Object>> reconciliation(){
        return jdbc.queryForList("""
          SELECT a.id AS account_id,a.user_id AS owner_user_id,a.currency,
            coalesce(sum(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END),0)::numeric(19,4)::text AS balance,
            count(e.id) AS entries
          FROM analytics_accounts a LEFT JOIN analytics_entries e ON e.account_id=a.id GROUP BY a.id ORDER BY a.id
          """);
    }
}
