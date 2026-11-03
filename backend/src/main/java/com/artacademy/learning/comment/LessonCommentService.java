package com.artacademy.learning.comment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.Errors;
import com.artacademy.learning.LessonAccess;
import com.artacademy.learning.comment.CommentDtos.CommentDto;
import com.artacademy.learning.comment.CommentDtos.InboxItemDto;
import com.artacademy.learning.comment.CommentDtos.InboxPageDto;
import com.artacademy.learning.comment.CommentDtos.LessonCommentsDto;
import com.artacademy.learning.comment.CommentDtos.ThreadDto;
import com.artacademy.learning.notification.LearningNotification;
import com.artacademy.learning.notification.NotificationRequest;
import com.artacademy.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * Bình luận dưới video bài học.
 *
 * <ul>
 * <li>Học viên: phải có quyền học khóa (như khi xem video) mới xem / viết bình luận của bài đó.</li>
 * <li>ADMIN / STAFF / INSTRUCTOR ("người hỗ trợ"): xem và trả lời bình luận ở mọi bài, có hộp thư bình luận.</li>
 * <li>Học viên bình luận → báo cho mọi người hỗ trợ. Có người trả lời → báo cho người viết bình luận gốc.</li>
 * </ul>
 * Thông báo được ghi sau khi bình luận đã commit (xem NotificationService), lỗi thông báo không làm mất bình luận.
 */
@Service
public class LessonCommentService {
	public static final Set<Role> SUPPORT_ROLES = Set.of(Role.ADMIN, Role.STAFF, Role.INSTRUCTOR);
	static final int MAX_BODY = 2000;
	private static final int EXCERPT = 120;

	private final LessonCommentRepository comments;
	private final CourseLessonRepository lessons;
	private final LessonAccess access;
	private final UserRepository users;
	private final EntityManager em;
	private final ApplicationEventPublisher events;

	public LessonCommentService(LessonCommentRepository comments, CourseLessonRepository lessons, LessonAccess access,
			UserRepository users, EntityManager em, ApplicationEventPublisher events) {
		this.comments = comments;
		this.lessons = lessons;
		this.access = access;
		this.users = users;
		this.em = em;
		this.events = events;
	}

	public static boolean isSupport(Role role) {
		return role != null && SUPPORT_ROLES.contains(role);
	}

	// ---------------------------------------------------------------- đọc

	@Transactional(readOnly = true)
	public LessonCommentsDto list(Integer userId, Role role, Integer lessonId) {
		CourseLesson lesson = requireReadable(userId, role, lessonId);
		List<LessonComment> roots = comments.findByLessonIdAndParentIdIsNullOrderByCreatedAtDescIdDesc(lessonId);
		Map<Long, List<LessonComment>> repliesByRoot = roots.isEmpty() ? Map.of()
				: comments.findByParentIdInOrderByCreatedAtAscIdAsc(roots.stream().map(LessonComment::getId).toList())
						.stream().collect(Collectors.groupingBy(LessonComment::getParentId));
		List<LessonComment> all = new ArrayList<>(roots);
		repliesByRoot.values().forEach(all::addAll);
		Map<Integer, User> authors = authorsOf(all);
		List<ThreadDto> threads = roots.stream()
				.map(r -> new ThreadDto(toDto(r, authors, userId), repliesByRoot.getOrDefault(r.getId(), List.of())
						.stream().map(c -> toDto(c, authors, userId)).toList()))
				.toList();
		return new LessonCommentsDto(lesson.getId(), lesson.getTitle(), courseIdOf(lesson),
				lesson.getModule().getCourse().getTitle(), isSupport(role), all.size(), threads);
	}

	/** Hộp thư của người hỗ trợ: bình luận gốc của học viên, mới nhất trước. */
	@Transactional(readOnly = true)
	public InboxPageDto inbox(Integer userId, Role role, boolean unansweredOnly, int page, int size) {
		requireSupport(role);
		Page<LessonComment> p = comments.inbox(unansweredOnly, SUPPORT_ROLES,
				PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
		List<LessonComment> roots = p.getContent();
		Map<Long, List<LessonComment>> replies = roots.isEmpty() ? Map.of()
				: comments.findByParentIdInOrderByCreatedAtAscIdAsc(roots.stream().map(LessonComment::getId).toList())
						.stream().collect(Collectors.groupingBy(LessonComment::getParentId));
		Map<Integer, User> authors = authorsOf(roots);
		Map<Integer, CourseLesson> lessonById = lessons
				.findAllById(roots.stream().map(LessonComment::getLessonId).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(CourseLesson::getId, Function.identity()));
		List<InboxItemDto> items = roots.stream().map(c -> {
			List<LessonComment> rs = replies.getOrDefault(c.getId(), List.of());
			boolean answered = rs.stream().anyMatch(r -> isSupport(r.getAuthorRole()));
			CourseLesson l = lessonById.get(c.getLessonId());
			return new InboxItemDto(toDto(c, authors, userId), c.getLessonId(), l == null ? "" : l.getTitle(),
					c.getCourseId(), l == null ? "" : l.getModule().getCourse().getTitle(), rs.size(), answered);
		}).toList();
		return new InboxPageDto(items, p.getTotalElements(), p.getNumber(), p.getSize(),
				comments.countUnanswered(SUPPORT_ROLES));
	}

	// ---------------------------------------------------------------- ghi

	@Transactional
	public CommentDto post(Integer userId, Role role, Integer lessonId, String body, Long parentId) {
		CourseLesson lesson = requireReadable(userId, role, lessonId);
		String text = body == null ? "" : body.strip();
		if (text.isEmpty()) {
			throw Errors.badRequest("Vui lòng nhập nội dung bình luận");
		}
		if (text.length() > MAX_BODY) {
			throw Errors.badRequest("Bình luận tối đa " + MAX_BODY + " ký tự");
		}
		LessonComment root = null;
		if (parentId != null) {
			LessonComment parent = comments.findById(parentId)
					.filter(p -> p.getLessonId().equals(lessonId))
					.orElseThrow(() -> Errors.notFound("Không tìm thấy bình luận cần trả lời"));
			root = parent.getParentId() == null ? parent
					: comments.findById(parent.getParentId()).orElseThrow(() -> Errors.notFound(
							"Không tìm thấy bình luận cần trả lời"));
		}
		User author = users.findById(userId).orElseThrow(() -> Errors.notFound("Không tìm thấy người dùng"));
		LessonComment c = new LessonComment();
		c.setLessonId(lesson.getId());
		c.setCourseId(courseIdOf(lesson));
		c.setAuthorUserId(userId);
		c.setAuthorRole(role == null ? Role.USER : role);
		c.setParentId(root == null ? null : root.getId());
		c.setBody(text);
		c = comments.saveAndFlush(c);
		notifyAbout(c, root, author, lesson);
		return toDto(c, Map.of(userId, author), userId);
	}

	private void notifyAbout(LessonComment c, LessonComment root, User author, CourseLesson lesson) {
		String where = lesson.getTitle() + " • " + lesson.getModule().getCourse().getTitle();
		String excerpt = excerpt(c.getBody());
		Long threadId = root == null ? c.getId() : root.getId();
		// Học viên viết (bình luận mới hoặc hỏi thêm trong luồng) → báo cho người hỗ trợ.
		if (!isSupport(c.getAuthorRole())) {
			List<Integer> staff = supportUserIds().stream().filter(id -> !id.equals(author.getId())).toList();
			if (!staff.isEmpty()) {
				events.publishEvent(NotificationRequest.toUsers(staff, LearningNotification.Type.NEW_COMMENT,
						"COMMENT:" + c.getId(),
						root == null ? author.getFullName() + " bình luận trong bài học"
								: author.getFullName() + " hỏi thêm trong bình luận",
						excerpt + "\n" + where, c.getCourseId(), c.getLessonId(), threadId));
			}
		}
		// Có người trả lời → báo cho người viết bình luận gốc (nếu không phải chính họ).
		if (root != null && !root.getAuthorUserId().equals(author.getId())) {
			events.publishEvent(NotificationRequest.toUser(root.getAuthorUserId(),
					LearningNotification.Type.COMMENT_REPLY, "REPLY:" + c.getId(),
					author.getFullName() + roleLabel(c.getAuthorRole()) + " đã trả lời bình luận của bạn",
					excerpt + "\n" + where, c.getCourseId(), c.getLessonId(), threadId));
		}
	}

	// ---------------------------------------------------------------- helpers

	/** Học viên phải có quyền học (403 nếu chưa mua); người hỗ trợ xem được mọi bài còn tồn tại. */
	private CourseLesson requireReadable(Integer userId, Role role, Integer lessonId) {
		if (isSupport(role)) {
			return lessons.findById(lessonId).orElseThrow(() -> Errors.notFound("Không tìm thấy bài học"));
		}
		access.requireLesson(userId, lessonId);
		CourseLesson l = lessons.findById(lessonId).orElseThrow(() -> Errors.notFound("Không tìm thấy bài học"));
		if (!l.isVisible() || l.getModule().getCourse().getStatus() == CourseStatus.DRAFT) {
			throw Errors.notFound("Không tìm thấy bài học");
		}
		return l;
	}

	private static void requireSupport(Role role) {
		if (!isSupport(role)) {
			throw Errors.forbidden("Chỉ Admin, Nhân viên hoặc Giảng viên được xem hộp thư bình luận");
		}
	}

	private List<Integer> supportUserIds() {
		return em.createQuery("select u.id from User u where u.role in :roles and u.isActive = true", Integer.class)
				.setParameter("roles", SUPPORT_ROLES).getResultList();
	}

	private Map<Integer, User> authorsOf(Collection<LessonComment> list) {
		Set<Integer> ids = new HashSet<>();
		list.forEach(c -> ids.add(c.getAuthorUserId()));
		return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
	}

	private static CommentDto toDto(LessonComment c, Map<Integer, User> authors, Integer viewerId) {
		User u = authors.get(c.getAuthorUserId());
		return new CommentDto(c.getId(), c.getParentId(), c.getAuthorUserId(), u == null ? "Người dùng" : u.getFullName(),
				u == null ? null : u.getAvatarUrl(), c.getAuthorRole().name(), c.getBody(), c.getCreatedAt(),
				c.getAuthorUserId().equals(viewerId));
	}

	private static Integer courseIdOf(CourseLesson l) {
		return l.getModule().getCourse().getId();
	}

	private static String excerpt(String s) {
		String one = s.replaceAll("\\s+", " ").strip();
		return one.length() <= EXCERPT ? one : one.substring(0, EXCERPT) + "…";
	}

	private static String roleLabel(Role role) {
		return switch (role) {
			case INSTRUCTOR -> " (Giảng viên)";
			case STAFF -> " (Nhân viên)";
			case ADMIN -> " (Admin)";
			default -> "";
		};
	}
}
