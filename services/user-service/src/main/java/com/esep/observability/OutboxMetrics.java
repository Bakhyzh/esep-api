package com.esep.observability;
import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;
@Component
public class OutboxMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong backlog=new AtomicLong(-1),oldest=new AtomicLong(-1),available=new AtomicLong(0);
    public OutboxMetrics(JdbcTemplate jdbc,MeterRegistry registry){
        this.jdbc=jdbc;
        Gauge.builder("esep.outbox.backlog",backlog,AtomicLong::get).register(registry);
        Gauge.builder("esep.outbox.oldest.seconds",oldest,AtomicLong::get).register(registry);
        Gauge.builder("esep.outbox.metrics.up",available,AtomicLong::get).register(registry);
    }
    @Scheduled(fixedDelayString="${esep.telemetry.poll-interval:15000}") public void sample(){
        try{
            var row=jdbc.queryForMap("SELECT count(*) AS pending,coalesce(extract(epoch FROM now()-min(created_at)),0)::bigint AS age FROM outbox_events WHERE published_at IS NULL");
            backlog.set(((Number)row.get("pending")).longValue());oldest.set(((Number)row.get("age")).longValue());available.set(1);
        }catch(RuntimeException error){available.set(0);backlog.set(-1);oldest.set(-1);}
    }
}
