package com.artacademy.course.dto;

import java.util.List;

public record AdminModuleDto(Integer id, Integer courseId, String title, int sortOrder, boolean hidden,
                             List<AdminLessonDto> lessons) {
}
