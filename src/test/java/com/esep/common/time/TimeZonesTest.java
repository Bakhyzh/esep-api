package com.esep.common.time;

import com.esep.common.exception.InvalidRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeZonesTest {

    @ParameterizedTest
    @ValueSource(strings = {"+05:00", "-03:00", "UTC+05:00", "GMT+05:00", "UT-02:00"})
    void fixedOffsets_areRejectedInEveryNotation(String id) {
        assertThatThrownBy(() -> TimeZones.requireRegion(ZoneId.of(id)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Z", "UTC", "GMT", "UTC+00:00", "GMT0"})
    void utcInEveryNotation_becomesUtc(String id) {
        assertThat(TimeZones.requireRegion(ZoneId.of(id))).isEqualTo(ZoneId.of("UTC"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Almaty", "Europe/Berlin", "America/New_York"})
    void regions_arePassedThrough(String id) {
        assertThat(TimeZones.requireRegion(ZoneId.of(id))).isEqualTo(ZoneId.of(id));
    }
}
