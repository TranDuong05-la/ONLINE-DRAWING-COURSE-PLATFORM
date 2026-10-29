package com.artacademy.course.dto;

import java.util.List;

public record AdminModuleDto(Long id, Long courseId, String title, int sortOrder, boolean hidden,
                             List<AdminLessonDto> lessons) {
}
