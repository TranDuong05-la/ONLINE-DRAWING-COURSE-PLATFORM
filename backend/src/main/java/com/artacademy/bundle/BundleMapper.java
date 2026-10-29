package com.artacademy.bundle;

import com.artacademy.bundle.dto.AdminBundleDto;
import com.artacademy.bundle.dto.BundleDto;
import com.artacademy.course.Course;
import com.artacademy.course.CourseMapper;
import com.artacademy.course.CourseStatus;
import com.artacademy.course.dto.CourseSummaryDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Đổi combo sang DTO. Gọi trong @Transactional. */
@Component
public class BundleMapper {

    private final BundleItemRepository itemRepository;
    private final CourseMapper courseMapper;

    public BundleMapper(BundleItemRepository itemRepository, CourseMapper courseMapper) {
        this.itemRepository = itemRepository;
        this.courseMapper = courseMapper;
    }

    /** Các khóa của từng combo (1 câu truy vấn cho tất cả combo), đúng thứ tự admin chọn. */
    public Map<Long, List<Course>> coursesByBundle(Collection<Long> bundleIds) {
        Map<Long, List<Course>> map = new HashMap<>();
        if (bundleIds.isEmpty()) {
            return map;
        }
        for (BundleItem item : itemRepository.findByBundles(bundleIds)) {
            map.computeIfAbsent(item.getBundle().getId(), k -> new ArrayList<>()).add(item.getCourse());
        }
        return map;
    }

    /** Combo hiện với học viên khi đang bật và mọi khóa trong combo đều PUBLISHED. */
    public boolean isVisible(CourseBundle bundle, List<Course> courses) {
        return bundle.isActive() && !courses.isEmpty()
                && courses.stream().allMatch(c -> c.getStatus() == CourseStatus.PUBLISHED);
    }

    public long originalTotal(List<Course> courses) {
        return courses.stream().mapToLong(Course::effectivePrice).sum();
    }

    public BundleDto toPublic(CourseBundle bundle, List<Course> courses) {
        long total = originalTotal(courses);
        List<CourseSummaryDto> summaries = courseMapper.toSummaries(courses);
        return new BundleDto(bundle.getId(), bundle.getCode(), bundle.getTitle(), bundle.getDescription(),
                bundle.getPrice(), total, Math.max(0, total - bundle.getPrice()), summaries);
    }

    public AdminBundleDto toAdmin(CourseBundle bundle, List<Course> courses) {
        long total = originalTotal(courses);
        List<String> warnings = new ArrayList<>();
        for (Course c : courses) {
            if (c.getStatus() != CourseStatus.PUBLISHED) {
                warnings.add("Khóa \"" + c.getTitle() + "\" đang ở trạng thái " + c.getStatus()
                        + " nên combo bị ẩn với học viên");
            }
        }
        if (bundle.getPrice() >= total) {
            warnings.add("Giá combo không còn rẻ hơn mua lẻ từng khóa, nên kiểm tra lại giá");
        }
        return new AdminBundleDto(bundle.getId(), bundle.getCode(), bundle.getTitle(), bundle.getDescription(),
                bundle.getPrice(), bundle.isActive(), total, Math.max(0, total - bundle.getPrice()),
                isVisible(bundle, courses), warnings, courseMapper.toSummaries(courses), bundle.getCreatedAt(),
                bundle.getUpdatedAt());
    }
}
