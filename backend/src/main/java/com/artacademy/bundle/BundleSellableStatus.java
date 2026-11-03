package com.artacademy.bundle;

/** Trạng thái bán của combo tại thời điểm lấy snapshot. */
public enum BundleSellableStatus {
    /** Combo đang bật và mọi khóa bên trong đều PUBLISHED: bán được. */
    AVAILABLE,
    /** Admin đã tắt combo. */
    INACTIVE,
    /** Có ít nhất một khóa trong combo không còn PUBLISHED (nháp hoặc ngừng bán). */
    COURSE_NOT_PUBLISHED
}
