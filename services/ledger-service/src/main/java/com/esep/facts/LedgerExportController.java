package com.esep.facts;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@Hidden
@RequiredArgsConstructor
@RequestMapping("/internal/ledger")
public class LedgerExportController {
    private final WriteFence fence;
    private final JdbcTemplate jdbc;
    private final LedgerFactWriter facts;
    @PostMapping("/write-fence") public Map<String,Object> fence(@RequestBody Toggle request){return fence.enabled(request.enabled());}
    @GetMapping("/write-fence") public Map<String,Object> state(){return fence.state();}
    @GetMapping("/postings-export") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<PostingEvent> export(@RequestParam long epoch,@RequestParam(defaultValue="0") @Min(0) long after,
            @RequestParam(defaultValue="100") @Min(1) @Max(1000) int limit){
        fence.requireExport(epoch);
        return jdbc.queryForList("SELECT id FROM transactions WHERE id>? AND status='COMPLETED' ORDER BY id LIMIT ?",Long.class,after,limit)
            .stream().map(id->facts.transactionSnapshot(id,LedgerFactWriter.backfillId("transaction",id,1))).toList();
    }
    @GetMapping("/accounts-export") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<AccountEvent> accounts(@RequestParam long epoch,@RequestParam(defaultValue="0") @Min(0) long after,
            @RequestParam(defaultValue="100") @Min(1) @Max(1000) int limit){
        fence.requireExport(epoch);
        return jdbc.queryForList("SELECT id FROM accounts WHERE id>? ORDER BY id LIMIT ?",Long.class,after,limit)
            .stream().map(id->{var account=facts.accountSnapshot(id,LedgerFactWriter.backfillId("account",id,0));
                return facts.accountSnapshot(id,LedgerFactWriter.backfillId("account",id,account.version()));}).toList();
    }
    @GetMapping("/reconciliation") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> reconciliation(@RequestParam long epoch){
        fence.requireExport(epoch);
        return jdbc.queryForList("""
          SELECT a.id AS account_id,a.user_id AS owner_user_id,a.currency,a.balance::text AS balance,count(e.id) AS entries
          FROM accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id
          GROUP BY a.id ORDER BY a.id
          """);
    }
    public record Toggle(boolean enabled){}
}
