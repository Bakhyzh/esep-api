package com.esep.common.web;

import java.util.List;

/**
 * Stable page shape for clients. Spring Data's Page is not returned directly:
 * its JSON is an implementation detail that may change between versions.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
