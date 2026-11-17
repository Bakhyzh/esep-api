package com.esep.ops;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.HashMap;
class MigrateDatabaseTest {
    private java.util.Map<String,String> settings(){
        var e=new HashMap<String,String>();e.put("MIGRATION_DB_URL","jdbc:postgresql://db.example.invalid/user_db?sslmode=verify-full");
        e.put("MIGRATION_DB_USERNAME","user_migrator");e.put("MIGRATION_DB_PASSWORD","m".repeat(32));return e;
    }
    @Test void productionRequiresVerifiedTlsAndExplicitMigrator(){
        var e=settings();MigrateDatabase.validate(e,false);
        e.put("MIGRATION_DB_URL","jdbc:postgresql://postgres:5432/user_db");
        assertThatThrownBy(()->MigrateDatabase.validate(e,false)).isInstanceOf(IllegalStateException.class);
        MigrateDatabase.validate(e,true);
        e.put("MIGRATION_DB_USERNAME","user_runtime");
        assertThatThrownBy(()->MigrateDatabase.validate(e,true)).isInstanceOf(IllegalStateException.class);
    }
    @Test void syntheticModeRejectsRemoteOrOtherServiceDatabase(){
        var e=settings();assertThatThrownBy(()->MigrateDatabase.validate(e,true)).isInstanceOf(IllegalStateException.class);
        e.put("MIGRATION_DB_URL","jdbc:postgresql://postgres:5432/other_db");
        assertThatThrownBy(()->MigrateDatabase.validate(e,true)).isInstanceOf(IllegalStateException.class);
    }
}
