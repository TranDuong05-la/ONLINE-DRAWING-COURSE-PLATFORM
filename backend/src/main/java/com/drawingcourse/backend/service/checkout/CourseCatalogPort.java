package com.drawingcourse.backend.service.checkout;

import java.util.Optional;

/** B-owned trusted local catalog read. Never implement with a remote call inside Checkout's transaction. */
@FunctionalInterface
public interface CourseCatalogPort {
    Optional<CourseOffer> findForCheckout(Integer courseId);

    record CourseOffer(Integer courseId, String name, long unitPriceVnd, boolean purchasable) {
        public CourseOffer {
            if (courseId == null || courseId <= 0 || name == null || name.isBlank() || unitPriceVnd < 0)
                throw new IllegalArgumentException("Invalid Course offer");
        }
    }
}
