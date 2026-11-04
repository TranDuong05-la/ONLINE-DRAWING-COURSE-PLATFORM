package com.drawingcourse.backend.service;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.integration.CourseSellableService;
import com.artacademy.course.integration.SellableCourse;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;

/** Read-only Cart projection of Member B's trusted published Course catalog. */
public final class CourseSellableCartCatalogAdapter implements CourseCartCatalogProvider {
    private final CourseSellableService courses;
    private final CourseKitRepository kits;

    public CourseSellableCartCatalogAdapter(CourseSellableService courses, CourseKitRepository kits) {
        this.courses = Objects.requireNonNull(courses);
        this.kits = Objects.requireNonNull(kits);
    }

    @Override
    public Optional<CourseListing> findCourse(Integer courseId) {
        if (courseId == null || courseId <= 0) throw new IllegalArgumentException("Course ID must be positive");
        // B dùng id INT (Integer) cho khóa học.
        Integer catalogId = courseId;
        final SellableCourse sellable;
        try {
            sellable = courses.getSellableCourse(catalogId);
        } catch (NotFoundException unavailable) {
            return Optional.empty();
        } catch (DataAccessException failure) {
            throw new CartCatalogUnavailableException("Course catalog could not be read", failure);
        }
        if (sellable == null) throw new CartCatalogUnavailableException("Course catalog returned no evidence");

        int mappedId = checkedCartCourseId(sellable.courseId());
        if (mappedId != courseId) throw new CartCatalogUnavailableException("Course catalog returned a mismatched ID");
        if (sellable.kits() == null) throw new CartCatalogUnavailableException("Course catalog returned incomplete kit evidence");
        if (!sellable.kits().isEmpty() || !kits.findByCourse(catalogId).isEmpty()) return Optional.empty();
        validatePrice(sellable);
        if (sellable.title() == null || sellable.title().isBlank())
            throw new CartCatalogUnavailableException("Course catalog returned an invalid name");
        return Optional.of(new CourseListing(courseId, sellable.title(), sellable.effectivePrice(), true));
    }

    @Override
    public boolean requiresGift(Integer courseId) {
        if (courseId == null || courseId <= 0) return false;
        try {
            return !kits.findByCourse(courseId).isEmpty() && courses.getSellableCourse(courseId) != null;
        } catch (NotFoundException unavailable) {
            return false;
        }
    }

    static int checkedCartCourseId(Integer catalogId) {
        return checkedCartCourseId(catalogId == null ? null : catalogId.longValue());
    }

    static int checkedCartCourseId(Long catalogId) {
        if (catalogId == null || catalogId <= 0) throw new IllegalArgumentException("Catalog Course ID must be positive");
        try {
            return Math.toIntExact(catalogId);
        } catch (ArithmeticException overflow) {
            throw new CartCatalogUnavailableException("Course catalog ID does not fit the Cart identifier", overflow);
        }
    }

    private static void validatePrice(SellableCourse course) {
        Long discount = course.discountPrice();
        if (course.price() <= 0 || course.effectivePrice() <= 0
                || discount != null && (discount <= 0 || discount >= course.price())) {
            throw new CartCatalogUnavailableException("Course catalog returned invalid price evidence");
        }
        long expected = discount == null ? course.price() : discount;
        if (course.effectivePrice() != expected)
            throw new CartCatalogUnavailableException("Course catalog returned inconsistent price evidence");
    }
}
