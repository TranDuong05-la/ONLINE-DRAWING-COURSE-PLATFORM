package com.artacademy.learning.claim;

/**
 * Không giữ chỗ được khóa cho Order: học viên đã sở hữu khóa, hoặc khóa đang được giữ cho một Order khác còn hạn.
 * Ném ra bên trong transaction của D, nên toàn bộ Order (và mọi claim khác của nó) bị rollback cùng lúc.
 */
public class CourseClaimConflictException extends IllegalStateException {

	public enum Reason {
		ALREADY_OWNED, PENDING_IN_ANOTHER_ORDER, CLAIM_ALREADY_CLOSED
	}

	private final Reason reason;
	private final Long courseId;

	public CourseClaimConflictException(Reason reason, Long courseId, String message) {
		super(message);
		this.reason = reason;
		this.courseId = courseId;
	}

	public Reason getReason() {
		return reason;
	}

	public Long getCourseId() {
		return courseId;
	}
}
