package com.drawingcourse.backend.service.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.CourseIncludedKit;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.integration.CourseSellableService;
import com.artacademy.course.integration.SellableCourse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CourseSellableCatalogAdapterTest {
    private CourseSellableService catalog;
    private CourseKitRepository kits;
    private CourseSellableCatalogAdapter adapter;

    @BeforeEach
    void setUp() {
        catalog = mock(CourseSellableService.class);
        kits = mock(CourseKitRepository.class);
        adapter = new CourseSellableCatalogAdapter(catalog, kits);
        when(kits.findByCourse(17)).thenReturn(List.of());
    }

    @Test
    void mapsPublishedDigitalCourseUsingTrustedTitleAndEffectivePrice() {
        when(catalog.getSellableCourse(17)).thenReturn(sellable(17, "Trusted title", 120_000, 89_000L, 89_000));

        CourseCatalogPort.CourseOffer offer = adapter.findForCheckout(17).orElseThrow();

        assertEquals(17, offer.courseId());
        assertEquals("Trusted title", offer.name());
        assertEquals(89_000, offer.unitPriceVnd());
        assertTrue(offer.purchasable());
        verify(catalog).getSellableCourse(17);
        verify(kits).findByCourse(17);
    }

    @Test
    void publishedCourseWithoutDiscountUsesBasePrice() {
        when(catalog.getSellableCourse(17)).thenReturn(sellable(17, "Digital", 120_000, null, 120_000));

        assertEquals(120_000, adapter.findForCheckout(17).orElseThrow().unitPriceVnd());
    }

    @Test
    void unpublishedUnavailableOrMissingCourseIsNotPurchasable() {
        when(catalog.getSellableCourse(17)).thenThrow(new NotFoundException("unavailable"));

        assertTrue(adapter.findForCheckout(17).isEmpty());
    }

    @Test
    void rejectsNullZeroAndNegativeCartIdentifiersBeforeCatalogAccess() {
        assertThrows(IllegalArgumentException.class, () -> adapter.findForCheckout(null));
        assertThrows(IllegalArgumentException.class, () -> adapter.findForCheckout(0));
        assertThrows(IllegalArgumentException.class, () -> adapter.findForCheckout(-17));
        verifyNoInteractions(catalog, kits);
    }

    @Test
    void rejectsLongCatalogIdsThatCannotBeRepresentedByDIntegerIds() {
        assertEquals(Integer.MAX_VALUE, CourseSellableCatalogAdapter.checkedDcourseId((long) Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> CourseSellableCatalogAdapter.checkedDcourseId((long) Integer.MAX_VALUE + 1));
        assertThrows(IllegalArgumentException.class, () -> CourseSellableCatalogAdapter.checkedDcourseId(0L));
        assertThrows(IllegalArgumentException.class, () -> CourseSellableCatalogAdapter.checkedDcourseId((Long) null));
    }

    @Test
    void rejectsKitBearingCourseEvenWhenBProjectionOmitsInactiveProductKit() {
        SellableCourse withNoActiveKits = sellable(17, "Kit course", 120_000, null, 120_000);
        when(catalog.getSellableCourse(17)).thenReturn(withNoActiveKits);
        when(kits.findByCourse(17)).thenReturn(List.of(mock(CourseIncludedKit.class)));

        assertTrue(adapter.findForCheckout(17).isEmpty());
    }

    @Test
    void rejectsCatalogEvidenceForDifferentCourseId() {
        when(catalog.getSellableCourse(17)).thenReturn(sellable(18, "Mismatch", 120_000, null, 120_000));

        assertThrows(CheckoutRejectedException.class, () -> adapter.findForCheckout(17));
    }

    @Test
    void rejectsInvalidOrInconsistentPriceAndMissingCatalogEvidence() {
        when(catalog.getSellableCourse(17)).thenReturn(sellable(17, "Zero", 0, null, 0));
        assertThrows(CheckoutRejectedException.class, () -> adapter.findForCheckout(17));

        when(catalog.getSellableCourse(17)).thenReturn(sellable(17, "Bad discount", 100, 101L, 101));
        assertThrows(CheckoutRejectedException.class, () -> adapter.findForCheckout(17));

        when(catalog.getSellableCourse(17)).thenReturn(null);
        assertThrows(CheckoutRejectedException.class, () -> adapter.findForCheckout(17));
    }

    @Test
    void rejectsBlankCourseName() {
        when(catalog.getSellableCourse(17)).thenReturn(sellable(17, " ", 100, null, 100));

        assertThrows(CheckoutRejectedException.class, () -> adapter.findForCheckout(17));
    }

    private static SellableCourse sellable(Integer id, String title, long base, Long discount, long effective) {
        return new SellableCourse(id, title, null, base, discount, effective, List.of(), List.of());
    }
}
