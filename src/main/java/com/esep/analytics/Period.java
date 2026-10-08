package com.esep.analytics;

import java.util.Locale;

/** Granularity of a spending report; the name maps to PostgreSQL date_trunc() units. */
public enum Period {
    DAY,
    WEEK,
    MONTH;

    String sqlUnit() {
        return name().toLowerCase(Locale.ROOT);
    }
}
