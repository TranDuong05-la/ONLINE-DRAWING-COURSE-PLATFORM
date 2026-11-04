package com.artacademy.course.integration;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import java.util.List;
import java.util.ArrayList;
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

    public SellableCourse requireSellable(Long courseId) {
        SellableCourse course = getSellableCourse(courseId);
        if (!course.isSellable()) throw new CourseNotSellableException(course.status(), "A required course kit is unavailable");
        return course;
    }

    /** Dùng chung cho combo (BundleSellableService). */
    public SellableCourse toSellable(Course course) {
        List<SellableCourse.SellableKit> kits = new ArrayList<>();
        List<SellableCourse.UnavailableKit> unavailable = new ArrayList<>();
        for (var kit : kitRepository.findByCourse(course.getId())) {
            var product = kit.getProduct();
            if (!product.isActive()) unavailable.add(new SellableCourse.UnavailableKit(product.getId(), kit.getQuantity(),
                    SellableCourse.UnavailableKit.INACTIVE));
            else if (product.getAvailable() < kit.getQuantity()) unavailable.add(new SellableCourse.UnavailableKit(product.getId(), kit.getQuantity(),
                    SellableCourse.UnavailableKit.INSUFFICIENT_STOCK));
            else kits.add(new SellableCourse.SellableKit(product.getId(), product.getSku(), product.getName(), kit.getQuantity()));
        }
        return new SellableCourse(course.getId(), course.getTitle(), course.getThumbnailUrl(), course.getPrice(),
                course.getDiscountPrice(), course.effectivePrice(), kits, unavailable);
    }
}
