package com.esep.analytics.dto;

import java.math.BigDecimal;
import java.time.YearMonth;

/** changePercent is null when the previous month had no spending (division by zero). */
public record MonthlyComparison(YearMonth month, BigDecimal total, BigDecimal previousTotal,
                                BigDecimal change, BigDecimal changePercent) {
}
