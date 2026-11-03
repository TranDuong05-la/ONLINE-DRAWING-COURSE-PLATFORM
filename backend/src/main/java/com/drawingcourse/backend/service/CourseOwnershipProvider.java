package com.drawingcourse.backend.service;

/** Read-only C-owned ownership lookup. The user ID must come from the authenticated principal. */
@FunctionalInterface
public interface CourseOwnershipProvider {
    boolean isOwnedBy(Integer authenticatedUserId, Integer courseId);
}
