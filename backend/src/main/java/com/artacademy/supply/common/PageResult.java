package com.artacademy.supply.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Cùng tên trường với PageResponse bên Flutter (content, totalElements, totalPages, number...). */
public record PageResult<T>(List<T> content, long totalElements, int totalPages, int size, int number,
        boolean first, boolean last, boolean empty) {

    public static <E, T> PageResult<T> of(Page<E> page, Function<E, T> mapper) {
        List<T> content = page.getContent().stream().map(mapper).toList();
        return new PageResult<>(content, page.getTotalElements(), page.getTotalPages(), page.getSize(),
                page.getNumber(), page.isFirst(), page.isLast(), content.isEmpty());
    }
}
