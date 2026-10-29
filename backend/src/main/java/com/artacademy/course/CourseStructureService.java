package com.artacademy.course;

import com.artacademy.common.BadRequestException;
import com.artacademy.common.NotFoundException;
import com.artacademy.common.UrlRules;
import com.artacademy.course.dto.AdminLessonDto;
import com.artacademy.course.dto.AdminModuleDto;
import com.artacademy.course.dto.LessonRequest;
import com.artacademy.course.dto.ModuleRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin quản lý module và bài học. Không có chức năng xóa: chỉ ẩn (hidden = true),
 * để dữ liệu tiến độ của người đã mua không bị hỏng.
 * Sau mỗi thay đổi, nếu khóa đang PUBLISHED thì kiểm tra lại điều kiện bán; vi phạm thì lỗi 400 và hoàn tác
 * (vì cả hàm nằm trong 1 transaction).
 */
@Service
@Transactional
public class CourseStructureService {

    private final CourseRepository courseRepository;
    private final CourseModuleRepository moduleRepository;
    private final CourseLessonRepository lessonRepository;
    private final CourseMapper mapper;
    private final CoursePublishValidator publishValidator;

    public CourseStructureService(CourseRepository courseRepository, CourseModuleRepository moduleRepository,
                                  CourseLessonRepository lessonRepository, CourseMapper mapper,
                                  CoursePublishValidator publishValidator) {
        this.courseRepository = courseRepository;
        this.moduleRepository = moduleRepository;
        this.lessonRepository = lessonRepository;
        this.mapper = mapper;
        this.publishValidator = publishValidator;
    }

    // ------------------------------------------------------------------ module

    public AdminModuleDto createModule(Long courseId, ModuleRequest req) {
        Course course = findCourse(courseId);
        // Module mới luôn nằm cuối danh sách
        CourseModule module = new CourseModule(course, req.title().trim(), moduleRepository.maxSortOrder(courseId) + 1);
        module.setHidden(Boolean.TRUE.equals(req.hidden()));
        moduleRepository.save(module);
        guardPublished(course);
        return mapper.toAdminModule(module, List.of());
    }

    public AdminModuleDto updateModule(Long moduleId, ModuleRequest req) {
        CourseModule module = findModule(moduleId);
        module.setTitle(req.title().trim());
        module.setHidden(Boolean.TRUE.equals(req.hidden()));
        guardPublished(module.getCourse());
        return mapper.toAdminModule(module, lessonRepository.findByModuleIdOrderBySortOrderAscIdAsc(moduleId));
    }

    public List<AdminModuleDto> reorderModules(Long courseId, List<Long> ids) {
        findCourse(courseId);
        List<CourseModule> modules = moduleRepository.findByCourseIdOrderBySortOrderAscIdAsc(courseId);
        applyOrder(modules, ids, CourseModule::getId, CourseModule::setSortOrder);
        return modules.stream()
                .sorted(java.util.Comparator.comparingInt(CourseModule::getSortOrder))
                .map(m -> mapper.toAdminModule(m, lessonRepository.findByModuleIdOrderBySortOrderAscIdAsc(m.getId())))
                .toList();
    }

    // ------------------------------------------------------------------ bài học

    public AdminLessonDto createLesson(Long moduleId, LessonRequest req) {
        CourseModule module = findModule(moduleId);
        CourseLesson lesson = new CourseLesson(module, req.title().trim(), lessonRepository.maxSortOrder(moduleId) + 1);
        applyLesson(lesson, req);
        lessonRepository.save(lesson);
        guardPublished(module.getCourse());
        return mapper.toAdminLesson(lesson);
    }

    public AdminLessonDto updateLesson(Long lessonId, LessonRequest req) {
        CourseLesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy bài học"));
        lesson.setTitle(req.title().trim());
        applyLesson(lesson, req);
        guardPublished(lesson.getModule().getCourse());
        return mapper.toAdminLesson(lesson);
    }

    public List<AdminLessonDto> reorderLessons(Long moduleId, List<Long> ids) {
        findModule(moduleId);
        List<CourseLesson> lessons = lessonRepository.findByModuleIdOrderBySortOrderAscIdAsc(moduleId);
        applyOrder(lessons, ids, CourseLesson::getId, CourseLesson::setSortOrder);
        return lessons.stream()
                .sorted(java.util.Comparator.comparingInt(CourseLesson::getSortOrder))
                .map(mapper::toAdminLesson)
                .toList();
    }

    // ------------------------------------------------------------------ nội bộ

    private void applyLesson(CourseLesson lesson, LessonRequest req) {
        lesson.setDescription(req.description() == null || req.description().isBlank() ? null : req.description().trim());
        lesson.setVideoUrl(UrlRules.normalize(req.videoUrl(), "Video"));
        lesson.setDurationSeconds(req.durationSeconds() == null ? 0 : req.durationSeconds());
        lesson.setMaterialUrl(UrlRules.normalize(req.materialUrl(), "Tài liệu"));
        lesson.setPreview(Boolean.TRUE.equals(req.preview()));
        lesson.setRequired(Boolean.TRUE.equals(req.required()));
        lesson.setHidden(Boolean.TRUE.equals(req.hidden()));
    }

    /** Khóa đang bán: không cho thay đổi làm khóa mất điều kiện bán (ẩn bài cuối, xóa video bài đang hiện...). */
    private void guardPublished(Course course) {
        if (course.getStatus() == CourseStatus.PUBLISHED) {
            publishValidator.assertPublishable(course, false);
        }
    }

    /** Gán sort_order = 1..n theo đúng thứ tự `ids`. `ids` phải khớp chính xác tập id hiện có. */
    private <T> void applyOrder(List<T> items, List<Long> ids, Function<T, Long> idOf,
                                java.util.function.ObjIntConsumer<T> setOrder) {
        Set<Long> unique = new HashSet<>(ids);
        Map<Long, T> byId = items.stream().collect(Collectors.toMap(idOf, Function.identity()));
        if (unique.size() != ids.size() || !unique.equals(byId.keySet())) {
            throw new BadRequestException(
                    "Danh sách id phải gồm đủ tất cả mục hiện có (kể cả mục đang ẩn), không thiếu và không trùng");
        }
        for (int i = 0; i < ids.size(); i++) {
            setOrder.accept(byId.get(ids.get(i)), i + 1);
        }
    }

    private Course findCourse(Long id) {
        return courseRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy khóa học"));
    }

    private CourseModule findModule(Long id) {
        return moduleRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy module"));
    }
}
