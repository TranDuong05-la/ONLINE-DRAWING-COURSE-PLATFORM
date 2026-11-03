package com.artacademy.bundle;

import com.artacademy.bundle.dto.AdminBundleDto;
import com.artacademy.bundle.dto.BundleRequest;
import com.artacademy.common.BadRequestException;
import com.artacademy.common.NotFoundException;
import com.artacademy.common.TextUtils;
import com.artacademy.course.Course;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin quản lý combo. Không xóa, chỉ bật/tắt. */
@Service
@Transactional
public class BundleAdminService {

    private final BundleRepository bundleRepository;
    private final BundleItemRepository itemRepository;
    private final CourseRepository courseRepository;
    private final BundleMapper mapper;

    public BundleAdminService(BundleRepository bundleRepository, BundleItemRepository itemRepository,
                              CourseRepository courseRepository, BundleMapper mapper) {
        this.bundleRepository = bundleRepository;
        this.itemRepository = itemRepository;
        this.courseRepository = courseRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AdminBundleDto> list() {
        List<CourseBundle> bundles = bundleRepository.findAllByOrderByIdDesc();
        Map<Long, List<Course>> courses = mapper.coursesByBundle(bundles.stream().map(CourseBundle::getId).toList());
        return bundles.stream().map(b -> mapper.toAdmin(b, courses.getOrDefault(b.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public AdminBundleDto get(Long id) {
        return toAdmin(find(id));
    }

    public AdminBundleDto create(BundleRequest req) {
        List<Course> courses = validateCourses(req);
        CourseBundle bundle = new CourseBundle();
        bundle.setCode(resolveCode(req.code(), null));
        apply(bundle, req);
        bundleRepository.save(bundle);
        saveItems(bundle, courses);
        return toAdmin(bundle);
    }

    public AdminBundleDto update(Long id, BundleRequest req) {
        CourseBundle bundle = find(id);
        List<Course> courses = validateCourses(req);
        if (req.code() != null && !req.code().isBlank()) {
            bundle.setCode(resolveCode(req.code(), id));
        }
        apply(bundle, req);
        bundleRepository.save(bundle);
        // Xóa danh sách khóa cũ rồi thêm danh sách mới (hàm xóa tự flush trước và clear sau)
        itemRepository.deleteByBundle(id);
        saveItems(bundleRepository.getReferenceById(id), courses);
        return toAdmin(find(id));
    }

    /** Bật/tắt combo. Tắt = ẩn khỏi học viên. */
    public AdminBundleDto setActive(Long id, boolean active) {
        CourseBundle bundle = find(id);
        bundle.setActive(active);
        return toAdmin(bundle);
    }

    // ------------------------------------------------------------------ nội bộ

    private void apply(CourseBundle bundle, BundleRequest req) {
        bundle.setTitle(req.title().trim());
        bundle.setDescription(req.description() == null || req.description().isBlank() ? null : req.description().trim());
        bundle.setPrice(req.price());
        if (req.active() != null) {
            bundle.setActive(req.active());
        }
    }

    /**
     * Quy tắc combo: tối thiểu 2 khóa khác nhau, tất cả phải tồn tại và đang PUBLISHED,
     * giá combo phải nhỏ hơn tổng giá thực bán của các khóa (nếu không thì combo vô nghĩa).
     * Trả về khóa theo đúng thứ tự admin chọn.
     */
    private List<Course> validateCourses(BundleRequest req) {
        List<Long> ids = req.courseIds();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BadRequestException("Một khóa học chỉ được chọn một lần trong combo");
        }
        Map<Long, Course> byId = new HashMap<>();
        courseRepository.findAllById(ids).forEach(c -> byId.put(c.getId(), c));

        List<Course> courses = new ArrayList<>();
        for (Long id : ids) {
            Course course = byId.get(id);
            if (course == null) {
                throw new BadRequestException("Khóa học id " + id + " không tồn tại");
            }
            if (course.getStatus() != CourseStatus.PUBLISHED) {
                throw new BadRequestException(
                        "Khóa \"" + course.getTitle() + "\" chưa xuất bản (hoặc đã ngừng bán) nên không thể đưa vào combo");
            }
            courses.add(course);
        }
        long total = mapper.originalTotal(courses);
        if (req.price() >= total) {
            throw new BadRequestException("Giá combo (" + req.price()
                    + ") phải nhỏ hơn tổng giá thực bán các khóa (" + total + ")");
        }
        return courses;
    }

    private void saveItems(CourseBundle bundle, List<Course> courses) {
        for (Course course : courses) {
            itemRepository.save(new BundleItem(bundle, courseRepository.getReferenceById(course.getId())));
        }
    }

    private String resolveCode(String requested, Long selfId) {
        if (requested == null || requested.isBlank()) {
            String code;
            do {
                code = TextUtils.randomCode("CB");
            } while (bundleRepository.existsByCode(code));
            return code;
        }
        String code = requested.trim();
        boolean duplicated = selfId == null
                ? bundleRepository.existsByCode(code)
                : bundleRepository.existsByCodeAndIdNot(code, selfId);
        if (duplicated) {
            throw new BadRequestException("Mã combo \"" + code + "\" đã tồn tại");
        }
        return code;
    }

    private CourseBundle find(Long id) {
        return bundleRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy combo"));
    }

    private AdminBundleDto toAdmin(CourseBundle bundle) {
        List<Course> courses = mapper.coursesByBundle(List.of(bundle.getId())).getOrDefault(bundle.getId(), List.of());
        return mapper.toAdmin(bundle, courses);
    }
}
