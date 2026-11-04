package com.artacademy.course.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.course.CourseLevel;
import com.artacademy.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
		"app.payment.vnpay.enabled=false",
		"app.payment.vnpay.session-api.enabled=false",
		"app.payment.vnpay.ipn.enabled=false",
		"app.payment.vnpay.query-dr.enabled=false"
})
class MandatoryKitAvailabilityTest extends IntegrationTestBase {
	@Autowired private CourseSellableService sellable;

	@Test
	void outOfStockMandatoryGiftIsReportedAndCourseCannotBeMarkedSellable() throws Exception {
		var course = publishedCourse("Mandatory kit", 120_000, null, CourseLevel.BASIC);
		var gift = newProduct("MANDATORY-" + System.nanoTime(), true);
		newKit(course, gift, 1);
		var offer = sellable.getSellableCourse(course.getId());
		assertEquals(CourseSellableStatus.KIT_UNAVAILABLE, offer.status());
		assertFalse(offer.isSellable());
		assertEquals(SellableCourse.UnavailableKit.INSUFFICIENT_STOCK, offer.unavailableKits().get(0).reason());
		getJson("/api/courses/" + course.getId()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.course.kitsUnavailable").value(true))
				.andExpect(jsonPath("$.data.kits.length()").value(0));
	}
}
