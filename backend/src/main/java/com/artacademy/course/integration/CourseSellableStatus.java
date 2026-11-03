package com.artacademy.course.integration;

/** Trạng thái bán của một khóa học (khóa đã PUBLISHED). Khóa không PUBLISHED thì không có snapshot (NotFoundException). */
public enum CourseSellableStatus {
    /** Mọi quà tặng kèm đã cấu hình đều còn bán (hoặc khóa không có quà). */
    AVAILABLE,
    /** Có ít nhất một quà tặng kèm đã ngừng bán hoặc không còn trong danh mục của E: khóa vẫn hiển thị nhưng không bán được. */
    KIT_UNAVAILABLE
}