package com.esep.common.time;

import com.esep.common.exception.InvalidRequestException;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

public final class TimeZones {

    // "UTC+05:00", "GMT-3", "UT+1": a prefix plus an offset, which PostgreSQL parses as a POSIX zone
    private static final Pattern POSIX_LIKE = Pattern.compile("^(UTC|GMT|UT)[+-].*");

    private TimeZones() {
    }

    /**
     * Only region ids like "Asia/Almaty" (and UTC). PostgreSQL reads offsets in AT TIME ZONE as POSIX,
     * where the sign is inverted: '+05:00' would silently mean UTC-5. "UTC+05:00" and "GMT+05:00" are
     * ZoneRegion objects in Java, not ZoneOffset, but PostgreSQL inverts them too. Fixed-offset zones
     * from the tz database ("Etc/GMT-5") are real zone names there and stay allowed.
     */
    public static ZoneId requireRegion(ZoneId zone) {
        if ((zone instanceof ZoneOffset || POSIX_LIKE.matcher(zone.getId()).matches())
                && !zone.normalized().equals(ZoneOffset.UTC)) {
            throw new InvalidRequestException("Use a region time zone id like Asia/Almaty, not an offset");
        }
        return zone.normalized().equals(ZoneOffset.UTC) ? ZoneId.of("UTC") : zone;
    }
}
