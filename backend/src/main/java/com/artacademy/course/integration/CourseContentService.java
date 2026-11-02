package com.artacademy.course.integration;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseModule;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ĐIỂM NỐI CHO C: lấy cấu trúc khóa và nội dung bài học (có link video/tài liệu).
 *
 * <p><b>C PHẢI TỰ KIỂM TRA người dùng đã mua khóa (hoặc bài là học thử) TRƯỚC KHI gọi các hàm ở đây.</b>
 * Service này không biết ai đang gọi nên không kiểm tra quyền học.
 *
 * <p>Khóa HIDDEN (ngừng bán) vẫn trả về bình thường để người đã mua tiếp tục học. Khóa DRAFT thì không.
 */
@Service
@Transactional(readOnly = true)
public class CourseContentService {

    private final CourseRepository courseRepository;
    private final CourseLessonRepository lessonRepository;

    public CourseContentService(CourseRepository courseRepository, CourseLessonRepository lessonRepository) {
        this.courseRepository = courseRepository;
        this.lessonRepository = lessonRepository;
    }

    /** Module/bài đang hiển thị, đúng thứ tự sort_order. */
    public CourseStructure getCourseStructure(Long courseId) {
        Course course = findLearnableCourse(courseId);
        Map<CourseModule, List<CourseStructure.LessonItem>> grouped = new LinkedHashMap<>();
        for (CourseLesson l : lessonRepository.findVisibleByCourse(courseId)) {
            grouped.computeIfAbsent(l.getModule(), m -> new ArrayList<>())
                    .add(new CourseStructure.LessonItem(l.getId(), l.getTitle(), l.getDurationSeconds(),
                            l.isRequired(), l.isPreview()));
        }
        List<CourseStructure.ModuleItem> modules = grouped.entrySet().stream()
                .map(e -> new CourseStructure.ModuleItem(e.getKey().getId(), e.getKey().getTitle(),
                        e.getKey().getSortOrder(), e.getValue()))
                .toList();
        return new CourseStructure(course.getId(), course.getTitle(), modules);
    }

    /**
     * Nội dung một bài. Bài đã bị ẩn vẫn đọc được (để dữ liệu tiến độ cũ của C không bị "mồ côi"),
     * nhưng sẽ không có trong getCourseStructure.
     */
    public LessonContent getLessonContent(Long lessonId) {
        CourseLesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy bài học"));
        Course course = findLearnableCourse(lesson.getModule().getCourse().getId());
        return new LessonContent(lesson.getId(), course.getId(), lesson.getTitle(), lesson.getDescription(),
                lesson.getVideoUrl(), lesson.getMaterialUrl(), lesson.getDurationSeconds(), lesson.isRequired());
    }

    private Course findLearnableCourse(Long courseId) {
        return courseRepository.findById(courseId)
                .filter(c -> c.getStatus() != CourseStatus.DRAFT)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy khóa học"));
    }
}
