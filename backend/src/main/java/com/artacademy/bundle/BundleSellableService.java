package com.artacademy.bundle;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.integration.CourseSellableService;
import com.artacademy.course.integration.SellableCourse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ĐIỂM NỐI CHO D: lấy thông tin combo để bán.
 * Khi khách mua combo, D lưu giá combo vào đơn; C cấp quyền học cho TỪNG khóa trong {@code courses}.
 */
@Service
@Transactional(readOnly = true)
public class BundleSellableService {

    public record SellableBundle(Long bundleId, String code, String title, long price, long originalTotal,
                                 List<SellableCourse> courses) {
    }

    private final BundleRepository bundleRepository;
    private final BundleMapper bundleMapper;
    private final CourseSellableService courseSellableService;

    public BundleSellableService(BundleRepository bundleRepository, BundleMapper bundleMapper,
                                 CourseSellableService courseSellableService) {
        this.bundleRepository = bundleRepository;
        this.bundleMapper = bundleMapper;
        this.courseSellableService = courseSellableService;
    }

    /**
     * @throws NotFoundException combo không tồn tại, đã tắt, hoặc có khóa không còn PUBLISHED
     */
    public SellableBundle getSellableBundle(Long bundleId) {
        CourseBundle bundle = bundleRepository.findById(bundleId)
                .orElseThrow(() -> new NotFoundException("Combo không tồn tại hoặc đã ngừng bán"));
        List<Course> courses = bundleMapper.coursesByBundle(List.of(bundleId)).getOrDefault(bundleId, List.of());
        if (!bundleMapper.isVisible(bundle, courses)) {
            throw new NotFoundException("Combo không tồn tại hoặc đã ngừng bán");
        }
        return new SellableBundle(bundle.getId(), bundle.getCode(), bundle.getTitle(), bundle.getPrice(),
                bundleMapper.originalTotal(courses),
                courses.stream().map(courseSellableService::toSellable).toList());
    }
}
