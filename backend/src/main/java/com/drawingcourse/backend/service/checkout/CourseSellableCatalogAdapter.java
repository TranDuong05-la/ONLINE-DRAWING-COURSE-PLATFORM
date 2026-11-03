package com.drawingcourse.backend.service.checkout;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.integration.CourseSellableService;
import com.artacademy.course.integration.SellableCourse;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Bridges Member B's trusted sellable catalog to D's immutable Checkout offer contract. */
@Component
@ConditionalOnProperty(name = "app.checkout.course-only.catalog-adapter.enabled", havingValue = "true")
public final class CourseSellableCatalogAdapter implements CourseCatalogPort {
    private final CourseSellableService sellableCourses;
    private final CourseKitRepository courseKits;

    public CourseSellableCatalogAdapter(CourseSellableService sellableCourses, CourseKitRepository courseKits) {
        this.sellableCourses = Objects.requireNonNull(sellableCourses, "sellableCourses");
        this.courseKits = Objects.requireNonNull(courseKits, "courseKits");
    }

    @Override
    public Optional<CourseOffer> findForCheckout(Integer courseId) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("Course ID must be a positive Integer");
        }

        long catalogId = courseId.longValue();
        final SellableCourse catalogCourse;
        try {
            catalogCourse = sellableCourses.getSellableCourse(catalogId);
        } catch (NotFoundException unavailable) {
            return Optional.empty();
        }
        if (catalogCourse == null) {
            throw unavailable("Course catalog returned no sellable evidence");
        }

        final int mappedId;
        try {
            mappedId = checkedDcourseId(catalogCourse.courseId());
        } catch (IllegalArgumentException invalidIdEvidence) {
            throw unavailable("Course catalog returned an invalid Course ID", invalidIdEvidence);
        }
        if (mappedId != courseId) {
            throw unavailable("Course catalog returned evidence for a different Course ID");
        }

        // B's sellable projection omits kits whose Products are inactive. The source association
        // still means physical fulfillment is required, so query all associations and fail closed.
        if (catalogCourse.kits() == null || !catalogCourse.kits().isEmpty()
                || !courseKits.findByCourse(catalogId).isEmpty()) {
            return Optional.empty();
        }

        validatePriceEvidence(catalogCourse);
        if (catalogCourse.title() == null || catalogCourse.title().isBlank()) {
            throw unavailable("Course catalog returned a blank Course name");
        }

        return Optional.of(new CourseOffer(mappedId, catalogCourse.title(), catalogCourse.effectivePrice(), true));
    }

    static int checkedDcourseId(Long catalogId) {
        if (catalogId == null || catalogId <= 0) {
            throw new IllegalArgumentException("Catalog Course ID must be positive");
        }
        try {
            return Math.toIntExact(catalogId);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Catalog Course ID does not fit Member D's Integer ID", overflow);
        }
    }

    private static void validatePriceEvidence(SellableCourse course) {
        if (course.price() <= 0 || course.effectivePrice() <= 0) {
            throw unavailable("Course catalog returned an invalid price");
        }
        Long discount = course.discountPrice();
        if (discount != null && (discount <= 0 || discount >= course.price())) {
            throw unavailable("Course catalog returned an invalid discount price");
        }
        long expectedEffective = discount == null ? course.price() : discount;
        if (course.effectivePrice() != expectedEffective) {
            throw unavailable("Course catalog returned inconsistent effective price evidence");
        }
    }

    private static CheckoutRejectedException unavailable(String message) {
        return new CheckoutRejectedException(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, message);
    }

    private static CheckoutRejectedException unavailable(String message, Throwable cause) {
        return new CheckoutRejectedException(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, message, cause);
    }
}
