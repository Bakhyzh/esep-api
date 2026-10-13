package com.esep.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodTest {

    @Test
    void sqlUnits_areValidDateTruncFields() {
        // passed to date_trunc() as a bind parameter: must be exactly what PostgreSQL accepts
        assertThat(Period.DAY.sqlUnit()).isEqualTo("day");
        assertThat(Period.WEEK.sqlUnit()).isEqualTo("week");
        assertThat(Period.MONTH.sqlUnit()).isEqualTo("month");
    }
}
