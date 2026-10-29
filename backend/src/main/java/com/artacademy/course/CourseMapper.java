package com.artacademy.course;

import com.artacademy.course.dto.AdminCourseDetailDto;
import com.artacademy.course.dto.AdminLessonDto;
import com.artacademy.course.dto.AdminModuleDto;
import com.artacademy.course.dto.CourseDetailDto;
import com.artacademy.course.dto.CourseSummaryDto;
import com.artacademy.course.dto.KitDto;
import com.artacademy.course.dto.PublicLessonDto;
import com.artacademy.course.dto.PublicModuleDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Đổi Entity sang DTO. Luôn gọi bên trong @Transactional (vì quan hệ lazy). */
@Component
public class CourseMapper {

    private final CourseModuleRepository moduleRepository;
    private final CourseLessonRepository lessonRepository;
    private final CourseKitRepository kitRepository;
    private final CoursePublishValidator publishValidator;

    public CourseMapper(CourseModuleRepository moduleRepository, CourseLessonRepository lessonRepository,
                        CourseKitRepository kitRepository, CoursePublishValidator publishValidator) {
        this.moduleRepository = moduleRepository;
        this.lessonRepository = lessonRepository;
        this.kitRepository = kitRepository;
        this.publishValidator = publishValidator;
    }

    // ---------------------------------------------------------------- danh sách / thẻ khóa học

    /** Đổi nhiều khóa cùng lúc: thống kê bài học và quà chỉ tốn 2 câu truy vấn cho cả danh sách. */
    public List<CourseSummaryDto> toSummaries(List<Course> courses) {
        if (courses.isEmpty()) {
            return List.of();
        }
        List<Long> ids = courses.stream().map(Course::getId).toList();

        Map<Long, long[]> stats = new HashMap<>();
        for (Object[] row : lessonRepository.visibleStats(ids)) {
            stats.put((Long) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        Set<Long> withKits = new HashSet<>(kitRepository.courseIdsWithActiveKits(ids));

        List<CourseSummaryDto> result = new ArrayList<>();
        for (Course c : courses) {
            long[] s = stats.getOrDefault(c.getId(), new long[]{0, 0});
            result.add(new CourseSummaryDto(c.getId(), c.getCode(), c.getSlug(), c.getTitle(),
                    c.getShortDescription(), c.getThumbnailUrl(), c.getPrice(), c.getDiscountPrice(),
                    c.effectivePrice(), c.getLevel(), c.getStatus(), withKits.contains(c.getId()),
                    s[0], s[1], c.getPublishedAt(), c.getCreatedAt(), c.getUpdatedAt()));
        }
        return result;
    }

    public CourseSummaryDto toSummary(Course course) {
        return toSummaries(List.of(course)).get(0);
    }

    // ---------------------------------------------------------------- học viên

    public CourseDetailDto toPublicDetail(Course course) {
        // Chỉ lấy bài hiển thị; module không còn bài hiển thị nào sẽ không xuất hiện (do gom theo bài)
        Map<CourseModule, List<PublicLessonDto>> grouped = new LinkedHashMap<>();
        for (CourseLesson lesson : lessonRepository.findVisibleByCourse(course.getId())) {
            grouped.computeIfAbsent(lesson.getModule(), m -> new ArrayList<>()).add(toPublicLesson(lesson));
        }
        List<PublicModuleDto> modules = grouped.entrySet().stream()
                .map(e -> new PublicModuleDto(e.getKey().getId(), e.getKey().getTitle(), e.getKey().getSortOrder(),
                        e.getValue()))
                .toList();

        // Quà của sản phẩm đã bị tắt thì không hiện cho học viên
        List<KitDto> kits = kitRepository.findByCourse(course.getId()).stream()
                .filter(k -> k.getProduct().isActive())
                .map(this::toKit)
                .toList();
        return new CourseDetailDto(toSummary(course), course.getDescription(), modules, kits);
    }

    private PublicLessonDto toPublicLesson(CourseLesson l) {
        boolean hasDocument = l.getMaterialUrl() != null && !l.getMaterialUrl().isBlank();
        // Quy tắc quan trọng: chỉ bài học thử mới lộ link video
        String previewVideoUrl = l.isPreview() ? l.getVideoUrl() : null;
        return new PublicLessonDto(l.getId(), l.getTitle(), l.getDescription(), l.getDurationSeconds(),
                l.isPreview(), l.isRequired(), hasDocument, previewVideoUrl);
    }

    // ---------------------------------------------------------------- admin

    public AdminCourseDetailDto toAdminDetail(Course course) {
        Map<Long, List<AdminLessonDto>> lessonsByModule = new HashMap<>();
        for (CourseLesson l : lessonRepository.findAllByCourse(course.getId())) {
            lessonsByModule.computeIfAbsent(l.getModule().getId(), k -> new ArrayList<>()).add(toAdminLesson(l));
        }
        // Lấy module riêng để module chưa có bài nào vẫn hiện ra cho admin
        List<AdminModuleDto> modules = moduleRepository.findByCourseIdOrderBySortOrderAscIdAsc(course.getId()).stream()
                .map(m -> new AdminModuleDto(m.getId(), course.getId(), m.getTitle(), m.getSortOrder(), m.isHidden(),
                        lessonsByModule.getOrDefault(m.getId(), List.of())))
                .toList();
        List<KitDto> kits = kitRepository.findByCourse(course.getId()).stream().map(this::toKit).toList();
        List<String> problems = publishValidator.findProblems(course, true);
        return new AdminCourseDetailDto(toSummary(course), course.getDescription(), modules, kits, problems);
    }

    public AdminLessonDto toAdminLesson(CourseLesson l) {
        return new AdminLessonDto(l.getId(), l.getModule().getId(), l.getTitle(), l.getDescription(), l.getVideoUrl(),
                l.getDurationSeconds(), l.getMaterialUrl(), l.getSortOrder(), l.isPreview(), l.isRequired(),
                l.isHidden());
    }

    public AdminModuleDto toAdminModule(CourseModule m, List<CourseLesson> lessons) {
        return new AdminModuleDto(m.getId(), m.getCourse().getId(), m.getTitle(), m.getSortOrder(), m.isHidden(),
                lessons.stream().map(this::toAdminLesson).toList());
    }

    public KitDto toKit(CourseIncludedKit k) {
        return new KitDto(k.getProduct().getId(), k.getProduct().getSku(), k.getProduct().getName(),
                k.getProduct().getThumbnailUrl(), k.getQuantity());
    }
}
