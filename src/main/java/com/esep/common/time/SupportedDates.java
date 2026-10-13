package com.esep.common.time;

import com.esep.common.exception.InvalidRequestException;

import java.time.LocalDate;

/**
 * Calendar days a client may ask about. ISO dates parse up to year +999999999, but
 * PostgreSQL timestamps end at year 294276 and LocalDate.MAX.plusDays(1) overflows:
 * without this check such a date became a 409 "data conflict" or a 500.
 */
public final class SupportedDates {

    public static final LocalDate MIN = LocalDate.of(1900, 1, 1);
    public static final LocalDate MAX = LocalDate.of(9999, 12, 31);

    private SupportedDates() {
    }

    public static LocalDate require(LocalDate day, String name) {
        if (day != null && (day.isBefore(MIN) || day.isAfter(MAX))) {
            throw new InvalidRequestException("'" + name + "' must be between " + MIN + " and " + MAX);
        }
        return day;
    }
}
