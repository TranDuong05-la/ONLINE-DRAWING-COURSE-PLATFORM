package com.artacademy.bundle;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.CourseStatus;
import com.artacademy.course.integration.CourseSellableService;
import com.artacademy.course.integration.SellableCourse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ĐIỂM NỐI CHO D: lấy thông tin combo để bán.
 *
 * Luồng khuyến nghị cho D:
 * 1) Giỏ hàng: getSnapshot(id) để hiện combo và nhớ definitionVersion.
 * 2) Checkout: requireSellable(id, expectedVersion) MỘT LẦN và dùng chính kết quả đó làm snapshot lưu vào Order
 *    (không đọc lần hai). Nếu combo đã đổi hoặc không còn bán, hàm ném lỗi 409 (xem bên dưới).
 *
 * Khi khách mua combo: D lưu giá combo vào đơn; C cấp quyền học cho TỪNG khóa trong snapshot.courses().
 */
@Service
@Transactional(readOnly = true)
public class BundleSellableService {

    /** Bản cũ, giữ lại cho tương thích. Chỉ trả combo đang bán được; nên dùng getSnapshot/requireSellable. */
    public record SellableBundle(Integer bundleId, String code, String title, long price, long originalTotal,
                                 int definitionVersion, List<SellableCourse> courses) {
    }

    private final BundleRepository bundleRepository;
    private final BundleItemRepository itemRepository;
    private final BundleMapper bundleMapper;
    private final CourseSellableService courseSellableService;

    public BundleSellableService(BundleRepository bundleRepository, BundleItemRepository itemRepository,
                                 BundleMapper bundleMapper, CourseSellableService courseSellableService) {
        this.bundleRepository = bundleRepository;
        this.itemRepository = itemRepository;
        this.bundleMapper = bundleMapper;
        this.courseSellableService = courseSellableService;
    }

    /**
     * Ảnh chụp combo kèm trạng thái bán (kể cả khi combo không bán được, để biết lý do).
     * @throws NotFoundException combo không tồn tại
     */
    public BundleSnapshot getSnapshot(Integer bundleId) {
        CourseBundle bundle = find(bundleId);
        List<BundleItem> items = itemRepository.findByBundles(List.of(bundleId));

        List<BundleSnapshot.Component> components = new ArrayList<>();
        List<Course> courses = new ArrayList<>();
        for (BundleItem item : items) {
            Course course = item.getCourse();
            courses.add(course);
            SellableCourse sellable = courseSellableService.toSellable(course);
            components.add(new BundleSnapshot.Component(course.getId(), item.getPosition(), course.getTitle(),
                    course.effectivePrice(), sellable.kits(), sellable.unavailableKits()));
        }

        BundleSellableStatus status = BundleSellableStatus.AVAILABLE;
        String reason = null;
        if (!bundle.isActive()) {
            status = BundleSellableStatus.INACTIVE;
            reason = "Combo đã ngừng bán";
        } else if (courses.isEmpty() || courses.stream().anyMatch(c -> c.getStatus() != CourseStatus.PUBLISHED)) {
            status = BundleSellableStatus.COURSE_NOT_PUBLISHED;
            reason = "Combo có khóa học không còn được bán";
        }
        return new BundleSnapshot(bundle.getId(), bundle.getCode(), bundle.getTitle(), bundle.getPrice(),
                bundle.getDefinitionVersion(), status, reason, bundleMapper.originalTotal(courses), components);
    }

    /**
     * Lấy snapshot của combo ĐANG BÁN ĐƯỢC và còn đúng phiên bản D đang giữ.
     * @throws NotFoundException combo không tồn tại
     * @throws BundleNotSellableException combo đã tắt hoặc có khóa không còn PUBLISHED (HTTP 409)
     * @throws BundleChangedException definitionVersion khác expectedVersion (HTTP 409)
     */
    public BundleSnapshot requireSellable(Integer bundleId, int expectedVersion) {
        BundleSnapshot snapshot = getSnapshot(bundleId);
        if (!snapshot.isSellable()) {
            throw new BundleNotSellableException(snapshot.status(), snapshot.statusReason());
        }
        if (snapshot.definitionVersion() != expectedVersion) {
            throw new BundleChangedException(expectedVersion, snapshot.definitionVersion());
        }
        return snapshot;
    }

    /**
     * @throws NotFoundException combo không tồn tại, đã tắt, hoặc có khóa không còn PUBLISHED
     */
    public SellableBundle getSellableBundle(Integer bundleId) {
        BundleSnapshot snapshot = getSnapshot(bundleId);
        if (!snapshot.isSellable()) {
            throw new NotFoundException("Combo không tồn tại hoặc đã ngừng bán");
        }
        List<SellableCourse> courses = itemRepository.findByBundles(List.of(bundleId)).stream()
                .map(i -> courseSellableService.toSellable(i.getCourse())).toList();
        return new SellableBundle(snapshot.bundleId(), snapshot.code(), snapshot.title(), snapshot.priceVnd(),
                snapshot.originalTotalVnd(), snapshot.definitionVersion(), courses);
    }

    private CourseBundle find(Integer id) {
        return bundleRepository.findById(id).orElseThrow(() -> new NotFoundException("Combo không tồn tại"));
    }
}
