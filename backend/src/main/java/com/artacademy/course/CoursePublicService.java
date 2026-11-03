package com.artacademy.course;

import com.artacademy.common.BadRequestException;
import com.artacademy.common.NotFoundException;
import com.artacademy.common.PageResponse;
import com.artacademy.course.dto.CourseDetailDto;
import com.artacademy.course.dto.CourseSummaryDto;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Nghiệp vụ phía học viên: chỉ nhìn thấy khóa PUBLISHED. */
@Service
@Transactional(readOnly = true)
public class CoursePublicService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CourseRepository courseRepository;
    private final CourseMapper mapper;

    public CoursePublicService(CourseRepository courseRepository, CourseMapper mapper) {
        this.courseRepository = courseRepository;
        this.mapper = mapper;
    }

    /**
     * @param sort newest (mặc định) | price_asc | price_desc (theo giá thực bán)
     */
    public PageResponse<CourseSummaryDto> list(String keyword, String level, String sort, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page phải >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size phải từ 1 đến " + MAX_PAGE_SIZE);
        }

        Specification<Course> spec = CourseSpecs.filter(CourseStatus.PUBLISHED, parseLevel(level), keyword);
        Pageable pageable;
        switch (sort == null || sort.isBlank() ? "newest" : sort.toLowerCase(Locale.ROOT)) {
            case "newest" -> pageable = PageRequest.of(page, size,
                    Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id")));
            case "price_asc" -> {
                spec = spec.and(CourseSpecs.orderByEffectivePrice(true));
                pageable = PageRequest.of(page, size);
            }
            case "price_desc" -> {
                spec = spec.and(CourseSpecs.orderByEffectivePrice(false));
                pageable = PageRequest.of(page, size);
            }
            default -> throw new BadRequestException("sort chỉ nhận: newest, price_asc, price_desc");
        }

        Page<Course> result = courseRepository.findAll(spec, pageable);
        return new PageResponse<>(mapper.toSummaries(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /** Khóa nháp/ngừng bán trả 404 như thể không tồn tại. */
    public CourseDetailDto getDetail(Long id) {
        Course course = courseRepository.findById(id)
                .filter(c -> c.getStatus() == CourseStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy khóa học"));
        return mapper.toPublicDetail(course);
    }

    private CourseLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        try {
            return CourseLevel.valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("level chỉ nhận: BASIC, ADVANCED");
        }
    }
}
