package com.artacademy.course;

public enum CourseStatus {
    DRAFT,      // Nháp: chỉ admin thấy
    PUBLISHED,  // Đang bán
    HIDDEN      // Ngừng bán: không hiện ở API public nhưng người đã mua vẫn học được
}
