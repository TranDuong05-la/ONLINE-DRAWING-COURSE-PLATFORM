package com.artacademy.learning.comment;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.artacademy.dto.ApiResponse;
import com.artacademy.enums.Role;
import com.artacademy.learning.comment.CommentDtos.CommentDto;
import com.artacademy.learning.comment.CommentDtos.CommentRequest;
import com.artacademy.learning.comment.CommentDtos.InboxPageDto;
import com.artacademy.learning.comment.CommentDtos.LessonCommentsDto;
import com.artacademy.security.AuthenticatedUserIdResolver;
import com.artacademy.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/learning")
public class LessonCommentController {
	private final AuthenticatedUserIdResolver currentUser;
	private final LessonCommentService service;
	public LessonCommentController(AuthenticatedUserIdResolver currentUser, LessonCommentService service) {
		this.currentUser = currentUser; this.service = service;
	}
	@GetMapping("/lessons/{lessonId}/comments")
	public ApiResponse<LessonCommentsDto> list(@PathVariable Long lessonId) {
		return ApiResponse.success("OK", service.list(currentUser.requireUserId(), currentRole(), lessonId));
	}
	@PostMapping("/lessons/{lessonId}/comments")
	public ApiResponse<CommentDto> post(@PathVariable Long lessonId, @Valid @RequestBody CommentRequest req) {
		return ApiResponse.success("Comment posted", service.post(currentUser.requireUserId(), currentRole(), lessonId, req.body(), req.parentId()));
	}
	@GetMapping("/comments/inbox")
	public ApiResponse<InboxPageDto> inbox(@RequestParam(defaultValue = "false") boolean unanswered,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success("OK", service.inbox(currentUser.requireUserId(), currentRole(), unanswered, page, size));
	}
	private static Role currentRole() {
		Authentication a = SecurityContextHolder.getContext().getAuthentication();
		return a != null && a.getPrincipal() instanceof AuthenticatedUserPrincipal p ? p.getRole() : Role.USER;
	}
}
