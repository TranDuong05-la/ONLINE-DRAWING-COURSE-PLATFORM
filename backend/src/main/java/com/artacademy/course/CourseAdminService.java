package com.artacademy.course;

import com.artacademy.common.BadRequestException;
import com.artacademy.common.NotFoundException;
import com.artacademy.common.PageResponse;
import com.artacademy.common.TextUtils;
import com.artacademy.common.UrlRules;
import com.artacademy.course.dto.AdminCourseDetailDto;
import com.artacademy.course.dto.CourseRequest;
import com.artacademy.course.dto.CourseSummaryDto;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Nghiệp vụ admin cho thông tin chung của khóa học (module/bài ở CourseStructureService). */
@Service
@Transactional
public class CourseAdminService {

    private final CourseRepository courseRepository;
    private final CourseMapper mapper;
    private final CoursePublishValidator publishValidator;

    public CourseAdminService(CourseRepository courseRepository, CourseMapper mapper,
                              CoursePublishValidator publishValidator) {
        this.courseRepository = courseRepository;
        this.mapper = mapper;
        this.publishValidator = publishValidator;
    }

    /** Admin thấy khóa ở mọi trạng thái; có thể lọc theo trạng thái / từ khóa. */
    @Transactional(readOnly = true)
    public PageResponse<CourseSummaryDto> list(CourseStatus status, String keyword, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page phải >= 0 và size từ 1 đến 100");
        }
        Page<Course> result = courseRepository.findAll(CourseSpecs.filter(status, null, keyword),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new PageResponse<>(mapper.toSummaries(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminCourseDetailDto get(Long id) {
        return mapper.toAdminDetail(find(id));
    }

    /** Khóa mới luôn bắt đầu ở trạng thái DRAFT. */
    public AdminCourseDetailDto create(CourseRequest req) {
        Course course = new Course();
        apply(course, req);
        course.setCode(resolveCode(req.code(), null));
        course.setSlug(uniqueSlug(req.title()));
        course.setStatus(CourseStatus.DRAFT);
        courseRepository.save(course);
        return mapper.toAdminDetail(course);
    }

    public AdminCourseDetailDto update(Long id, CourseRequest req) {
        Course course = find(id);
        apply(course, req);
        if (req.code() != null && !req.code().isBlank()) {
            course.setCode(resolveCode(req.code(), id));
        }
        // Khóa đang bán thì không được sửa thành trạng thái vi phạm (ví dụ xóa mô tả ngắn)
        if (course.getStatus() == CourseStatus.PUBLISHED) {
            publishValidator.assertPublishable(course, false);
        }
        return mapper.toAdminDetail(course);
    }

    /**
     * Đổi trạng thái:
     *  DRAFT -> PUBLISHED (phải đủ điều kiện), PUBLISHED <-> HIDDEN.
     *  Đã từng xuất bản thì không quay lại DRAFT; khóa nháp chưa xuất bản thì không chuyển thẳng sang HIDDEN.
     *  Mỗi lần sang PUBLISHED đều kiểm tra lại điều kiện.
     */
    public AdminCourseDetailDto changeStatus(Long id, CourseStatus target) {
        Course course = find(id);
        CourseStatus current = course.getStatus();
        if (current == target) {
            throw new BadRequestException("Khóa học đã ở trạng thái " + target);
        }
        switch (target) {
            case DRAFT -> throw new BadRequestException(
                    "Khóa học đã từng xuất bản nên không thể quay về nháp (chỉ chuyển giữa PUBLISHED và HIDDEN)");
            case PUBLISHED -> {
                publishValidator.assertPublishable(course, true);
                if (course.getPublishedAt() == null) {
                    course.setPublishedAt(LocalDateTime.now());
                }
                course.setStatus(CourseStatus.PUBLISHED);
            }
            case HIDDEN -> {
                if (current == CourseStatus.DRAFT) {
                    throw new BadRequestException("Khóa nháp chưa từng xuất bản nên không thể chuyển sang ngừng bán");
                }
                course.setStatus(CourseStatus.HIDDEN);
            }
        }
        return mapper.toAdminDetail(course);
    }

    // ------------------------------------------------------------------ nội bộ

    private Course find(Long id) {
        return courseRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy khóa học"));
    }

    private void apply(Course course, CourseRequest req) {
        // Quy tắc giá: giá giảm (nếu có) phải nhỏ hơn giá gốc
        if (req.discountPrice() != null && req.discountPrice() >= req.price()) {
            throw new BadRequestException("Giá giảm phải nhỏ hơn giá gốc");
        }
        course.setTitle(req.title().trim());
        course.setShortDescription(blankToNull(req.shortDescription()));
        course.setDescription(blankToNull(req.description()));
        course.setThumbnailUrl(UrlRules.normalize(req.thumbnailUrl(), "Ảnh bìa"));
        course.setPrice(req.price());
        course.setDiscountPrice(req.discountPrice());
        course.setLevel(req.level());
    }

    /** Mã do admin nhập (phải chưa tồn tại) hoặc tự sinh khi tạo mới. */
    private String resolveCode(String requested, Long selfId) {
        if (requested == null || requested.isBlank()) {
            String code;
            do {
                code = TextUtils.randomCode("KH");
            } while (courseRepository.existsByCode(code));
            return code;
        }
        String code = requested.trim();
        boolean duplicated = selfId == null
                ? courseRepository.existsByCode(code)
                : courseRepository.existsByCodeAndIdNot(code, selfId);
        if (duplicated) {
            throw new BadRequestException("Mã khóa học \"" + code + "\" đã tồn tại");
        }
        return code;
    }

    private String uniqueSlug(String title) {
        String base = TextUtils.slugify(title);
        String slug = base;
        int i = 2;
        while (courseRepository.existsBySlug(slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
