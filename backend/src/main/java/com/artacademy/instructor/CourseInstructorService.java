package com.artacademy.instructor;

import com.artacademy.course.Course;
import com.artacademy.course.CourseMapper;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.dto.CourseSummaryDto;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.Errors;
import com.artacademy.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADMIN phân công / gỡ giảng viên khỏi khóa; giảng viên xem danh sách khóa của mình. */
@Service
public class CourseInstructorService {

    public record InstructorDto(Integer userId, String fullName, String email, LocalDateTime assignedAt) {
    }

    private final CourseInstructorRepository assignments;
    private final CourseRepository courses;
    private final UserRepository users;
    private final CourseMapper mapper;

    public CourseInstructorService(CourseInstructorRepository assignments, CourseRepository courses,
                                   UserRepository users, CourseMapper mapper) {
        this.assignments = assignments;
        this.courses = courses;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<InstructorDto> list(Integer courseId) {
        requireCourse(courseId);
        List<CourseInstructor> rows = assignments.findByCourseIdOrderByAssignedAtAscIdAsc(courseId);
        Map<Integer, User> byId = users.findAllById(rows.stream().map(CourseInstructor::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream().map(r -> {
            User u = byId.get(r.getUserId());
            return new InstructorDto(r.getUserId(), u == null ? null : u.getFullName(),
                    u == null ? null : u.getEmail(), r.getAssignedAt());
        }).toList();
    }

    /** Chỉ phân công được tài khoản đang hoạt động có role INSTRUCTOR. Phân công lại người đã có thì báo 409. */
    @Transactional
    public InstructorDto assign(Integer courseId, String email, Integer adminUserId) {
        requireCourse(courseId);
        User u = users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> Errors.notFound("Không tìm thấy tài khoản " + email.trim()));
        if (u.getRole() != Role.INSTRUCTOR) {
            throw Errors.badRequest("Tài khoản này không phải giảng viên. Đổi role sang INSTRUCTOR trước khi phân công");
        }
        if (!Boolean.TRUE.equals(u.getIsActive())) {
            throw Errors.badRequest("Tài khoản giảng viên đang bị khóa");
        }
        if (assignments.existsByCourseIdAndUserId(courseId, u.getId())) {
            throw Errors.conflict("Giảng viên này đã được phân công cho khóa học");
        }
        CourseInstructor saved = assignments.saveAndFlush(new CourseInstructor(courseId, u.getId(), adminUserId));
        return new InstructorDto(u.getId(), u.getFullName(), u.getEmail(), saved.getAssignedAt());
    }

    @Transactional
    public void unassign(Integer courseId, Integer userId) {
        CourseInstructor row = assignments.findByCourseIdAndUserId(courseId, userId)
                .orElseThrow(() -> Errors.notFound("Giảng viên này chưa được phân công cho khóa học"));
        assignments.delete(row);
    }

    /** Khóa của giảng viên (mọi trạng thái), mới nhất trước. courseIds null = ADMIN, xem mọi khóa. */
    @Transactional(readOnly = true)
    public List<CourseSummaryDto> myCourses(List<Integer> courseIds) {
        List<Course> found = courseIds == null ? courses.findAll() : courses.findAllById(courseIds);
        return mapper.toSummaries(found.stream()
                .sorted(Comparator.comparing(Course::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Course::getId, Comparator.reverseOrder()))
                .toList());
    }

    private void requireCourse(Integer courseId) {
        if (!courses.existsById(courseId)) {
            throw Errors.notFound("Không tìm thấy khóa học");
        }
    }
}
