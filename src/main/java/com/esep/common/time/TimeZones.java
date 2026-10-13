package com.esep.common.time;

import com.esep.common.exception.InvalidRequestException;

import java.time.ZoneId;
import java.time.ZoneOffset;

public final class TimeZones {

    private TimeZones() {
    }

    /**
     * Only region ids like "Asia/Almaty" (and UTC). PostgreSQL reads offsets in AT TIME ZONE as POSIX,
     * where the sign is inverted: '+05:00' would silently mean UTC-5.
     * normalized(): "UTC+05:00" and "GMT+05:00" are ZoneRegion objects, not ZoneOffset, but they are
     * fixed offsets all the same and PostgreSQL inverts them too.
     */
    public static ZoneId requireRegion(ZoneId zone) {
        if (zone.normalized() instanceof ZoneOffset offset && !offset.equals(ZoneOffset.UTC)) {
            throw new InvalidRequestException("Use a region time zone id like Asia/Almaty, not an offset");
        }
        return zone.normalized().equals(ZoneOffset.UTC) ? ZoneId.of("UTC") : zone;
    }
}
