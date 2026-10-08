package com.esep;

import com.esep.support.IntegrationTest;
import org.junit.jupiter.api.Test;

// context starts, Flyway applies all migrations, Hibernate validates entities against the schema
class EsepApiApplicationTests extends IntegrationTest {

    @Test
    void contextLoads() {
    }
}
