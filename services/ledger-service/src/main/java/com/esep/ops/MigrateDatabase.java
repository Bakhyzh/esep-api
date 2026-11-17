package com.esep.ops;

import java.net.URI;
import java.util.Map;
import org.flywaydb.core.Flyway;
import com.esep.security.ProductionSafety;

/** Dedicated one-off entry point. No Spring context, HTTP server, consumer or outbox publisher starts. */
public final class MigrateDatabase {
    private MigrateDatabase(){}
    public static void main(String[] args){
        boolean local=args.length==1 && "--local-synthetic".equals(args[0]);
        if(args.length>0 && !local)throw new IllegalArgumentException("Unsupported migration arguments");
        Map<String,String> environment=System.getenv();validate(environment,local);
        var flyway=Flyway.configure().dataSource(environment.get("MIGRATION_DB_URL"),
                environment.get("MIGRATION_DB_USERNAME"),environment.get("MIGRATION_DB_PASSWORD"))
                .locations(local?new String[]{"classpath:db/ledger","classpath:db/local"}:new String[]{"classpath:db/ledger"}).cleanDisabled(true).initSql("SET lock_timeout='5s'; SET statement_timeout='60s'").validateMigrationNaming(true).load();
        flyway.migrate();flyway.validate();
        System.out.println("ledger schema migration and validation completed; no runtime service started");
    }
    static void validate(Map<String,String> e,boolean local){
        for(String key:java.util.List.of("MIGRATION_DB_URL","MIGRATION_DB_USERNAME","MIGRATION_DB_PASSWORD"))
            if(e.getOrDefault(key,"").isBlank())throw new IllegalStateException("Explicit migration credentials required");
        String url=e.get("MIGRATION_DB_URL");
        if(local){
            URI uri;
            try{uri=URI.create(url.substring(5));}catch(RuntimeException error){throw new IllegalStateException("Invalid synthetic database endpoint");}
            if(!url.startsWith("jdbc:postgresql://") || uri.getHost()==null || !java.util.Set.of("postgres","localhost","127.0.0.1").contains(uri.getHost())
                    || !"/ledger_db".equals(uri.getPath()) || uri.getUserInfo()!=null || uri.getRawQuery()!=null)
                throw new IllegalStateException("Local migration accepts only the isolated service database");
        }else ProductionSafety.requireDatabaseTls(url);
        String role=e.get("MIGRATION_DB_USERNAME");
        if(!role.endsWith("_migrator") || role.contains("bootstrap") || e.get("MIGRATION_DB_PASSWORD").length()<24)
            throw new IllegalStateException("Explicit restricted migration role and strong credentials required");
    }
}
