package com.artacademy.course.dto;

import java.util.List;

public record PublicModuleDto(Long id, String title, int sortOrder, List<PublicLessonDto> lessons) {
}
