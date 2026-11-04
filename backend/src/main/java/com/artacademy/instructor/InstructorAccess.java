package com.artacademy.instructor;

import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseModuleRepository;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.Errors;
import com.artacademy.learning.SubmissionRepository;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserIdResolver;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chặn quyền ở mọi API /api/instructor/**: SecurityConfig chỉ biết role (INSTRUCTOR / ADMIN), còn "được phân công
 * khóa nào" kiểm tra ở đây. ADMIN đi qua mọi khóa. Khóa không được phân công trả 404 (không lộ khóa đó có tồn tại).
 * Role đọc lại từ bảng users ở mỗi request, nên bị đổi role khỏi INSTRUCTOR là mất quyền ngay.
 */
@Component
@Transactional(readOnly = true)
public class InstructorAccess {

    /** Người đang gọi API: id và có phải ADMIN không. */
    public record Caller(Integer userId, boolean admin) {
    }

    private final AuthenticatedUserIdResolver currentUser;
    private final UserRepository users;
    private final CourseInstructorRepository assignments;
    private final CourseModuleRepository modules;
    private final CourseLessonRepository lessons;
    private final SubmissionRepository submissions;

    public InstructorAccess(AuthenticatedUserIdResolver currentUser, UserRepository users,
                            CourseInstructorRepository assignments, CourseModuleRepository modules,
                            CourseLessonRepository lessons, SubmissionRepository submissions) {
        this.currentUser = currentUser;
        this.users = users;
        this.assignments = assignments;
        this.modules = modules;
        this.lessons = lessons;
        this.submissions = submissions;
    }

    public Caller caller() {
        User u = users.findById(currentUser.requireUserId())
                .orElseThrow(() -> Errors.forbidden("Tài khoản không tồn tại"));
        if (!Boolean.TRUE.equals(u.getIsActive())
                || (u.getRole() != Role.INSTRUCTOR && u.getRole() != Role.ADMIN)) {
            throw Errors.forbidden("Chỉ giảng viên mới dùng được chức năng này");
        }
        return new Caller(u.getId(), u.getRole() == Role.ADMIN);
    }

    /** Khóa giảng viên được dạy. ADMIN trả null (= mọi khóa). */
    public List<Integer> courseIds(Caller c) {
        return c.admin() ? null : assignments.courseIdsOf(c.userId());
    }

    public Caller requireCourse(Integer courseId) {
        Caller c = caller();
        if (!c.admin() && !assignments.existsByCourseIdAndUserId(courseId, c.userId())) {
            throw notAssigned();
        }
        return c;
    }

    public Caller requireModule(Integer moduleId) {
        Integer courseId = modules.findById(moduleId).map(m -> m.getCourse().getId())
                .orElseThrow(() -> Errors.notFound("Không tìm thấy module"));
        return requireCourse(courseId);
    }

    public Caller requireLesson(Integer lessonId) {
        Integer courseId = lessons.findById(lessonId).map(l -> l.getModule().getCourse().getId())
                .orElseThrow(() -> Errors.notFound("Không tìm thấy bài học"));
        return requireCourse(courseId);
    }

    public Caller requireSubmission(Long submissionId) {
        Integer courseId = submissions.findById(submissionId).map(s -> s.getCourseId())
                .orElseThrow(() -> Errors.notFound("Không tìm thấy bài nộp"));
        return requireCourse(courseId);
    }

    private static RuntimeException notAssigned() {
        return Errors.notFound("Không tìm thấy khóa học trong danh sách bạn được phân công");
    }
}
