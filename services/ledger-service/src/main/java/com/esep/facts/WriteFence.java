package com.esep.facts;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WriteFence {
    private final JdbcTemplate jdbc;
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireWritable(){
        // Separate two-int advisory namespace; all financial replicas take this before mutations.
        jdbc.query("SELECT pg_advisory_xact_lock_shared(38102,1)",rs->{});
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT writes_enabled FROM ledger_control WHERE id=1",Boolean.class)))
            throw new FencedException();
    }
    @Transactional public Map<String,Object> enabled(boolean enabled){
        jdbc.query("SELECT pg_advisory_xact_lock(38102,1)",rs->{});
        jdbc.update("UPDATE ledger_control SET writes_enabled=?,epoch=epoch+1,changed_at=now() WHERE id=1",enabled);
        return state();
    }
    public Map<String,Object> state(){return jdbc.queryForMap("SELECT writes_enabled,epoch FROM ledger_control WHERE id=1");}
    public void requireExport(long expectedEpoch){
        var state=state();
        if(Boolean.TRUE.equals(state.get("writes_enabled")) || ((Number)state.get("epoch")).longValue()!=expectedEpoch)
            throw new com.esep.common.exception.ConflictException(com.esep.common.exception.ErrorCode.CONFLICT,"A current fenced export epoch is required");
    }
    public static class FencedException extends RuntimeException { public FencedException(){super("Financial writes are fenced for a controlled snapshot");} }
}
