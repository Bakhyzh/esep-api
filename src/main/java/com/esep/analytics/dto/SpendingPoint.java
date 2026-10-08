package com.esep.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SpendingPoint(LocalDate periodStart, BigDecimal total, long operations) {
}
