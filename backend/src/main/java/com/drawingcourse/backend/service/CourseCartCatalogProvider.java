package com.drawingcourse.backend.service;

import java.util.Optional;

/** Read-only B-owned catalog boundary; implementations must not call remote services under a Cart lock. */
@FunctionalInterface
public interface CourseCartCatalogProvider {
    Optional<CourseListing> findCourse(Integer courseId);

    /** True when the Course exists but is not sold here only because it carries a physical gift (no delivery yet). */
    default boolean requiresGift(Integer courseId) { return false; }

    record CourseListing(Integer courseId, String name, long currentPriceVnd, boolean active) {
        public CourseListing {
            if (courseId == null || courseId <= 0 || name == null || name.isBlank() || currentPriceVnd < 0) {
                throw new IllegalArgumentException("Invalid Course catalog listing");
            }
        }
    }
}
