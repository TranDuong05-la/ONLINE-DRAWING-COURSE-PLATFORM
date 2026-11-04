package com.artacademy.learning.comment;

import java.time.LocalDateTime;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CommentDtos {
	private CommentDtos() { }
	public record CommentDto(Long id, Long parentId, Integer authorUserId, String authorName, String authorAvatarUrl,
			String authorRole, String body, LocalDateTime createdAt, boolean mine) { }
	public record ThreadDto(CommentDto comment, List<CommentDto> replies) { }
	public record LessonCommentsDto(Long lessonId, String lessonTitle, Long courseId, String courseTitle,
			boolean canModerate, int total, List<ThreadDto> threads) { }
	public record InboxItemDto(CommentDto comment, Long lessonId, String lessonTitle, Long courseId,
			String courseTitle, int replyCount, boolean answered) { }
	public record InboxPageDto(List<InboxItemDto> items, long total, int page, int size, long unansweredCount) { }
	public record CommentRequest(@NotBlank(message = "Comment cannot be blank")
			@Size(max = LessonCommentService.MAX_BODY, message = "Comment exceeds 2000 characters") String body,
			Long parentId) { }
}
