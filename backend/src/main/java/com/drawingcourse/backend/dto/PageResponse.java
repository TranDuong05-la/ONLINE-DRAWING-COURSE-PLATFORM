package com.drawingcourse.backend.dto;

import java.util.List;

/** Stable page metadata contract shared with the mobile PageResponse model. */
public record PageResponse<T>(List<T> content, long totalElements, int totalPages,
                              int size, int number, boolean first, boolean last, boolean empty) {
    public PageResponse { content = List.copyOf(content); }
}