package com.drawingcourse.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseSellableCartCatalogAdapterTest {
    @Test
    void checkedCourseIdConversionRejectsOverflowAndInvalidIds() {
        assertThat(CourseSellableCartCatalogAdapter.checkedCartCourseId(2_147_483_647L))
                .isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> CourseSellableCartCatalogAdapter.checkedCartCourseId(2_147_483_648L))
                .isInstanceOf(CartCatalogUnavailableException.class);
        assertThatThrownBy(() -> CourseSellableCartCatalogAdapter.checkedCartCourseId(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CourseSellableCartCatalogAdapter.checkedCartCourseId((Long) null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
