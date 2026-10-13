package com.esep.common.time;

import com.esep.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedDatesTest {

    @Test
    void boundsAndNull_arePassedThrough() {
        assertThat(SupportedDates.require(SupportedDates.MIN, "from")).isEqualTo(SupportedDates.MIN);
        assertThat(SupportedDates.require(SupportedDates.MAX, "to")).isEqualTo(SupportedDates.MAX);
        assertThat(SupportedDates.require(null, "to")).isNull();
    }

    @Test
    void outsideTheRange_isRejectedWithTheParameterName() {
        assertThatThrownBy(() -> SupportedDates.require(LocalDate.of(10000, 1, 1), "to"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("'to'");
        assertThatThrownBy(() -> SupportedDates.require(LocalDate.of(1899, 12, 31), "from"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("'from'");
    }
}
