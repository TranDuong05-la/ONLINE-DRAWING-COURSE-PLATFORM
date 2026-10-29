package com.artacademy.course.integration;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ĐIỂM NỐI CHO D: lấy thông tin khóa học để bán. Gọi trực tiếp (inject service), không qua HTTP.
 * Ví dụ ở CartService của D: SellableCourse c = courseSellableService.getSellableCourse(courseId);
 */
@Service
@Transactional(readOnly = true)
public class CourseSellableService {

    private final CourseRepository courseRepository;
    private final CourseKitRepository kitRepository;

    public CourseSellableService(CourseRepository courseRepository, CourseKitRepository kitRepository) {
        this.courseRepository = courseRepository;
        this.kitRepository = kitRepository;
    }

    /**
     * @throws NotFoundException khóa không tồn tại hoặc không ở trạng thái PUBLISHED (nháp / ngừng bán)
     */
    public SellableCourse getSellableCourse(Long courseId) {
        Course course = courseRepository.findById(courseId)
                .filter(c -> c.getStatus() == CourseStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Khóa học không tồn tại hoặc đã ngừng bán"));
        return toSellable(course);
    }

    /** Dùng chung cho combo (BundleSellableService). */
    public SellableCourse toSellable(Course course) {
        List<SellableCourse.SellableKit> kits = kitRepository.findByCourse(course.getId()).stream()
                .filter(k -> k.getProduct().isActive())
                .map(k -> new SellableCourse.SellableKit(k.getProduct().getId(), k.getProduct().getSku(),
                        k.getProduct().getName(), k.getQuantity()))
                .toList();
        return new SellableCourse(course.getId(), course.getTitle(), course.getThumbnailUrl(), course.getPrice(),
                course.getDiscountPrice(), course.effectivePrice(), kits);
    }
}
