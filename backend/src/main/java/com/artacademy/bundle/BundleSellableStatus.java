package com.artacademy.bundle;

/** Trạng thái bán của combo tại thời điểm lấy snapshot. Thứ tự ưu tiên khi nhiều lý do: INACTIVE, COURSE_NOT_PUBLISHED, KIT_UNAVAILABLE. */
public enum BundleSellableStatus {
    /** Combo đang bật, mọi khóa bên trong đều PUBLISHED và mọi quà tặng kèm còn bán: bán được. */
    AVAILABLE,
    /** Admin đã tắt combo. */
    INACTIVE,
    /** Có ít nhất một khóa trong combo không còn PUBLISHED (nháp hoặc ngừng bán). */
    COURSE_NOT_PUBLISHED,
    /** Có ít nhất một quà tặng kèm của khóa trong combo đã ngừng bán hoặc không còn trong danh mục của E. Combo vẫn hiển thị nhưng không bán được. */
    KIT_UNAVAILABLE
}