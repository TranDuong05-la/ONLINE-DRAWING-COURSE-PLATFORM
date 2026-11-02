package com.artacademy.course;

import com.artacademy.common.BadRequestException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Quy tắc "khóa học đủ điều kiện để bán". Dùng ở 2 nơi:
 *  - Khi chuyển trạng thái sang PUBLISHED (kiểm tra đầy đủ, gồm cả module rỗng).
 *  - Khi sửa nội dung một khóa đang PUBLISHED (không cho sửa thành trạng thái vi phạm, ví dụ ẩn bài cuối cùng).
 *    Lúc này KHÔNG kiểm tra module rỗng, vì admin phải tạo module trước rồi mới thêm bài vào được.
 */
@Component
public class CoursePublishValidator {

    private final CourseModuleRepository moduleRepository;
    private final CourseLessonRepository lessonRepository;

    public CoursePublishValidator(CourseModuleRepository moduleRepository, CourseLessonRepository lessonRepository) {
        this.moduleRepository = moduleRepository;
        this.lessonRepository = lessonRepository;
    }

    /** Trả về danh sách điều kiện còn thiếu (rỗng = đủ điều kiện). */
    public List<String> findProblems(Course course, boolean checkEmptyModules) {
        List<String> problems = new ArrayList<>();

        if (course.getShortDescription() == null || course.getShortDescription().isBlank()) {
            problems.add("cần nhập mô tả ngắn cho khóa học");
        }

        List<CourseLesson> visibleLessons = lessonRepository.findAllByCourse(course.getId()).stream()
                .filter(CourseLesson::isVisible)
                .toList();
        if (visibleLessons.isEmpty()) {
            problems.add("cần ít nhất 1 bài học đang hiển thị");
        }
        for (CourseLesson lesson : visibleLessons) {
            if (lesson.getVideoUrl() == null || lesson.getVideoUrl().isBlank()) {
                problems.add("bài học \"" + lesson.getTitle() + "\" chưa có video");
            }
        }

        if (checkEmptyModules) {
            Set<Long> modulesWithLessons = new HashSet<>();
            visibleLessons.forEach(l -> modulesWithLessons.add(l.getModule().getId()));
            for (CourseModule module : moduleRepository.findByCourseIdOrderBySortOrderAscIdAsc(course.getId())) {
                if (!module.isHidden() && !modulesWithLessons.contains(module.getId())) {
                    problems.add("module \"" + module.getTitle() + "\" chưa có bài học nào đang hiển thị");
                }
            }
        }
        return problems;
    }

    /** Ném lỗi 400 (message tiếng Việt, liệt kê hết các điều kiện thiếu) nếu chưa đủ điều kiện. */
    public void assertPublishable(Course course, boolean checkEmptyModules) {
        List<String> problems = findProblems(course, checkEmptyModules);
        if (!problems.isEmpty()) {
            throw new BadRequestException("Khóa học chưa đủ điều kiện xuất bản: " + String.join("; ", problems));
        }
    }
}
