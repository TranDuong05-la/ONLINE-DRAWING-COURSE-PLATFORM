package com.artacademy.common;

import java.util.List;
import org.springframework.data.domain.Page;

/** Kết quả phân trang trả cho Flutter: { content, page, size, totalElements, totalPages }. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
