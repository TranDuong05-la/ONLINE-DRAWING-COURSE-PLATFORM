package com.artacademy.learning.claim;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.drawingcourse.backend.service.checkout.CoursePurchasePort;

/**
 * Cổng Checkout khóa lẻ của D ({@link CoursePurchasePort}), chuyển thẳng sang {@link CourseClaimService}.
 *
 * <p>Chỉ đăng ký khi D bật Checkout (app.checkout.course-only.enabled=true). Lý do: CoursePurchasePort kế thừa
 * ActivePendingCoursePurchaseProvider; nếu luôn đăng ký thì sẽ có 2 bean cùng loại với bean
 * JpaActivePendingCoursePurchaseProvider của D. Kiểm tra "đang chờ thanh toán" ở đây dùng bảng course_claims của C
 * (nguồn chính xác, có unique chặn hai Order giữ cùng một khóa).
 */
@Component
@ConditionalOnProperty(name = "app.checkout.course-only.enabled", havingValue = "true")
public class CoursePurchasePortAdapter implements CoursePurchasePort {
	private final CourseClaimService claims;

	public CoursePurchasePortAdapter(CourseClaimService claims) {
		this.claims = claims;
	}

	@Override
	public boolean isOwnedBy(Integer authenticatedUserId, Integer courseId) {
		return claims.isOwnedBy(authenticatedUserId, courseId);
	}

	@Override
	public boolean hasActivePendingPurchase(Integer authenticatedUserId, Integer courseId, Instant observedAt) {
		return claims.hasActivePendingPurchase(authenticatedUserId, courseId, observedAt);
	}

	@Override
	public void claimForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId, Instant observedAt,
			Instant expiresAt) {
		claims.claimForOrder(authenticatedUserId, courseId, orderId, observedAt, expiresAt);
	}

	@Override
	public void confirmForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId, Instant confirmedAt) {
		claims.confirmForOrder(authenticatedUserId, courseId, orderId, confirmedAt);
	}

	@Override
	public void releaseForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId, String terminalReason,
			Instant releasedAt) {
		claims.releaseForOrder(authenticatedUserId, courseId, orderId, terminalReason, releasedAt);
	}
}
