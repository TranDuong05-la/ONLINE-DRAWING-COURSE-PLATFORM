package com.artacademy.instructor;

import com.artacademy.course.Course;
import com.artacademy.course.CourseRepository;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev: phân công instructor.test@artnest.com (A tạo) cho 2 khóa mẫu có bài tập (B tạo khóa, C tạo đề).
 * Chạy sau các seeder khác (ApplicationReadyEvent); chỉ thêm, chạy lại không trùng.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class InstructorDemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(InstructorDemoSeeder.class);
    private static final String INSTRUCTOR_EMAIL = "instructor.test@artnest.com";
    private static final Set<String> DEMO_COURSES = Set.of("KH-WATERCOLOR", "KH-PORTRAIT");

    private final UserRepository users;
    private final CourseRepository courses;
    private final CourseInstructorRepository assignments;

    public InstructorDemoSeeder(UserRepository users, CourseRepository courses,
                                CourseInstructorRepository assignments) {
        this.users = users;
        this.courses = courses;
        this.assignments = assignments;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        Optional<User> instructor = users.findByEmail(INSTRUCTOR_EMAIL).filter(u -> u.getRole() == Role.INSTRUCTOR);
        Optional<User> admin = users.findAll().stream().filter(u -> u.getRole() == Role.ADMIN).findFirst();
        if (instructor.isEmpty() || admin.isEmpty()) {
            return;
        }
        for (Course c : courses.findAll()) {
            if (DEMO_COURSES.contains(c.getCode())
                    && !assignments.existsByCourseIdAndUserId(c.getId(), instructor.get().getId())) {
                assignments.save(new CourseInstructor(c.getId(), instructor.get().getId(), admin.get().getId()));
                log.info("Demo: assigned {} to course {}", INSTRUCTOR_EMAIL, c.getCode());
            }
        }
    }
}
