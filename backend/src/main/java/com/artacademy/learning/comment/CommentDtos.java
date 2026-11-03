package com.artacademy.learning.comment;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Dữ liệu vào/ra của API bình luận video (phần C). */
public final class CommentDtos {
	private CommentDtos() {
	}

	/** {@code authorRole}: USER / INSTRUCTOR / STAFF / ADMIN. {@code mine}: bình luận của người đang xem. */
	public record CommentDto(Long id, Long parentId, Integer authorUserId, String authorName, String authorAvatarUrl,
			String authorRole, String body, LocalDateTime createdAt, boolean mine) {
	}

	/** Bình luận gốc và các trả lời (cũ trước, mới sau). */
	public record ThreadDto(CommentDto comment, List<CommentDto> replies) {
	}

	/** {@code canModerate}: người xem là Admin / Nhân viên / Giảng viên. */
	public record LessonCommentsDto(Integer lessonId, String lessonTitle, Integer courseId, String courseTitle,
			boolean canModerate, int total, List<ThreadDto> threads) {
	}

	public record InboxItemDto(CommentDto comment, Integer lessonId, String lessonTitle, Integer courseId,
			String courseTitle, int replyCount, boolean answered) {
	}

	public record InboxPageDto(List<InboxItemDto> items, long total, int page, int size, long unansweredCount) {
	}

	public record CommentRequest(@NotBlank(message = "Vui lòng nhập nội dung bình luận")
	@Size(max = LessonCommentService.MAX_BODY, message = "Bình luận tối đa 2000 ký tự") String body, Long parentId) {
	}
}
