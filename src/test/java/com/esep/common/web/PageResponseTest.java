package com.esep.common.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void totalPages_roundsUp() {
        assertThat(PageResponse.of(List.of(), 0, 20, 41).totalPages()).isEqualTo(3);
        assertThat(PageResponse.of(List.of(), 0, 20, 40).totalPages()).isEqualTo(2);
        assertThat(PageResponse.of(List.of(), 0, 20, 1).totalPages()).isEqualTo(1);
    }

    @Test
    void noElements_meansNoPages() {
        PageResponse<String> page = PageResponse.of(List.of(), 0, 20, 0);

        assertThat(page.totalPages()).isZero();
        assertThat(page.content()).isEmpty();
    }

    @Test
    void hugeTotal_doesNotOverflowInTheRounding() {
        // (total + size - 1) is computed in long
        assertThat(PageResponse.of(List.of(), 0, 100, Integer.MAX_VALUE).totalPages()).isEqualTo(21_474_837);
    }
}
