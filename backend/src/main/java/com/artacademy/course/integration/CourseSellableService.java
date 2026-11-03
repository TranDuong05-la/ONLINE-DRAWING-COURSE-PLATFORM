package com.artacademy.course.integration;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.CourseKitLookup;
import com.artacademy.course.CourseKitLookup.ResolvedKit;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import java.util.ArrayList;
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
    private final CourseKitLookup kitLookup;

    public CourseSellableService(CourseRepository courseRepository, CourseKitLookup kitLookup) {
        this.courseRepository = courseRepository;
        this.kitLookup = kitLookup;
    }

    /**
     * @throws NotFoundException khóa không tồn tại hoặc không ở trạng thái PUBLISHED (nháp / ngừng bán)
     */
    public SellableCourse getSellableCourse(Integer courseId) {
        Course course = courseRepository.findById(courseId)
                .filter(c -> c.getStatus() == CourseStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Khóa học không tồn tại hoặc đã ngừng bán"));
        return toSellable(course);
    }

    /** Dùng chung cho combo (BundleSellableService). */
    public SellableCourse toSellable(Course course) {
        List<SellableCourse.SellableKit> kits = new ArrayList<>();
        List<SellableCourse.UnavailableKit> unavailable = new ArrayList<>();
        for (ResolvedKit rk : kitLookup.resolveAll(course.getId())) {
            if (rk.isActive()) {
                kits.add(new SellableCourse.SellableKit(rk.product().id(), rk.product().sku(), rk.product().name(),
                        rk.kit().getQuantity()));
            } else {
                unavailable.add(new SellableCourse.UnavailableKit(rk.kit().getProductId(), rk.kit().getQuantity(),
                        rk.product() == null ? SellableCourse.UnavailableKit.NOT_FOUND
                                : SellableCourse.UnavailableKit.INACTIVE));
            }
        }
        return new SellableCourse(course.getId(), course.getTitle(), course.getThumbnailUrl(), course.getPrice(),
                course.getDiscountPrice(), course.effectivePrice(), kits, unavailable);
    }
}
