package com.esep.ops;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real fresh cluster, actual entrypoint scripts, four independent Flyway histories and restricted logins. */
@Testcontainers
class DatabaseRolesTest {
    static final Path ROOT=root();
    static Path root() {
        Path p=Path.of("").toAbsolutePath();
        while(p!=null && !Files.isRegularFile(p.resolve("compose.platform.yaml")))p=p.getParent();
        if(p==null)throw new IllegalStateException("Repository configuration is required for operations tests");return p;
    }
    static final Map<String,String> PASSWORDS=new HashMap<>();
    static {
        for(String prefix:List.of("USER","LEDGER","NOTIFICATION","ANALYTICS")) {
            PASSWORDS.put(prefix+"_DB_PASSWORD",UUID.randomUUID().toString());
            PASSWORDS.put(prefix+"_MIGRATION_PASSWORD",UUID.randomUUID().toString());
            if(!prefix.equals("USER"))PASSWORDS.put(prefix+"_IDENTITY_DB_PASSWORD",UUID.randomUUID().toString());
        }
        for(String role:List.of("BACKUP","OPS","RESTORE"))PASSWORDS.put("PLATFORM_"+role+"_PASSWORD",UUID.randomUUID().toString());
    }
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse(
        "postgres@sha256:562a6e0a66b5136bc28bef5944bbc2ddddf8ea0058d4016b96cee63f2edaf143"))
        .withDatabaseName("postgres").withUsername("platform_bootstrap").withPassword(UUID.randomUUID().toString())
        .withEnv(PASSWORDS).withCommand("postgres","-c","shared_preload_libraries=pg_stat_statements")
        .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("infrastructure/platform/postgres-init.sql")),"/docker-entrypoint-initdb.d/10-platform.sql")
        .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("infrastructure/ops/roles.sql")),"/docker-entrypoint-initdb.d/20-ops.sql");
    static String url(String database){return "jdbc:postgresql://"+PG.getHost()+":"+PG.getMappedPort(5432)+"/"+database;}
    static Connection connection(String database,String role,String key)throws SQLException{return DriverManager.getConnection(url(database),role,PASSWORDS.get(key));}
    @BeforeAll static void migrateAllFreshPublicSchemas() {
        for(String name:List.of("user","ledger","notification","analytics")) {
            var flyway=Flyway.configure().dataSource(url(name+"_db"),name+"_migrator",PASSWORDS.get(name.toUpperCase()+"_MIGRATION_PASSWORD"))
                .locations("filesystem:"+ROOT.resolve("services/"+name+"-service/src/main/resources/db/"+name)).load();
            flyway.migrate();flyway.validate();
        }
    }
    static void denied(Connection connection,String query) {
        SQLException error=catchThrowableOfType(()->{try(var statement=connection.createStatement()){statement.execute(query);}},SQLException.class);
        assertThat((Throwable)error).isNotNull();assertThat(error.getSQLState()).isEqualTo("42501");
    }
    @Test void runtimeCannotModifyMigrationHistoryOrAnotherServiceDatabase()throws Exception {
        try(var c=connection("user_db","user_runtime","USER_DB_PASSWORD")) {
            denied(c,"UPDATE flyway_schema_history SET description='forbidden' WHERE installed_rank=-1");
            denied(c,"CREATE TABLE forbidden_runtime_ddl(id bigint)");
            try(var s=c.createStatement();var r=s.executeQuery("SHOW statement_timeout")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("5s");}
        }
        assertThatThrownBy(()->connection("ledger_db","user_runtime","USER_DB_PASSWORD")).isInstanceOf(SQLException.class);
        try(var c=connection("ledger_db","ledger_runtime","LEDGER_DB_PASSWORD")) {
            denied(c,"INSERT INTO ledger_principals(id) VALUES(45)");
            denied(c,"SELECT nextval('ledger_identity_bindings_id_seq')");
            denied(c,"DELETE FROM ledger_entries WHERE id=-1");
        }
    }
    @Test void identityProjectionHasOnlyItsOwnedWritesAndSequence()throws Exception {
        try(var c=connection("ledger_db","ledger_projector","LEDGER_IDENTITY_DB_PASSWORD")) {
            try(var s=c.createStatement()) {
                s.executeUpdate("INSERT INTO ledger_principals(id) VALUES(45)");
                s.executeUpdate("INSERT INTO ledger_identity_bindings(user_id,issuer,subject,linked_by) VALUES(45,'verified-test-issuer','verified-test-subject','role-test')");
            }
            denied(c,"INSERT INTO accounts(user_id,currency) VALUES(45,'KZT')");
            denied(c,"SELECT nextval('accounts_id_seq')");
        }
    }
    @Test void monitoringBackupAndRestorePrivilegesAreSeparated()throws Exception {
        try(var c=connection("user_db","platform_ops","PLATFORM_OPS_PASSWORD")) {
            try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM monitoring.pg_stat_statements")){assertThat(r.next()).isTrue();}
            denied(c,"SELECT * FROM users");
            denied(c,"DELETE FROM users WHERE id=-1");
        }
        try(var c=connection("user_db","platform_backup","PLATFORM_BACKUP_PASSWORD")) {
            try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM users")){assertThat(r.next()).isTrue();}
            denied(c,"UPDATE users SET display_name='forbidden' WHERE id=-1");
        }
        assertThatThrownBy(()->connection("ledger_db","platform_restore","PLATFORM_RESTORE_PASSWORD")).isInstanceOf(SQLException.class);
    }
    @Test void connectionBudgetAndStatementTimeoutFailWithoutPartialCommit()throws Exception {
        try(var first=connection("user_db","platform_backup","PLATFORM_BACKUP_PASSWORD");
            var second=connection("user_db","platform_backup","PLATFORM_BACKUP_PASSWORD")) {
            SQLException error=catchThrowableOfType(()->connection("user_db","platform_backup","PLATFORM_BACKUP_PASSWORD"),SQLException.class);
            assertThat((Throwable)error).isNotNull();assertThat(error.getSQLState()).isEqualTo("53300");
            try(var monitor=connection("user_db","platform_ops","PLATFORM_OPS_PASSWORD")){assertThat(monitor.isValid(2)).isTrue();}
        }
        String email="timeout-"+UUID.randomUUID()+"@example.invalid";
        try(var c=connection("user_db","user_runtime","USER_DB_PASSWORD")) {
            c.setAutoCommit(false);
            try(var insert=c.prepareStatement("INSERT INTO users(email) VALUES(?)")){insert.setString(1,email);insert.executeUpdate();}
            SQLException error=catchThrowableOfType(()->{try(var statement=c.createStatement()){statement.execute("SELECT pg_sleep(10)");}},SQLException.class);
            assertThat((Throwable)error).isNotNull();assertThat(error.getSQLState()).isEqualTo("57014");c.rollback();
            try(var count=c.prepareStatement("SELECT count(*) FROM users WHERE email=?")) {
                count.setString(1,email);try(var result=count.executeQuery()){result.next();assertThat(result.getLong(1)).isZero();}
            }
        }
    }

}
