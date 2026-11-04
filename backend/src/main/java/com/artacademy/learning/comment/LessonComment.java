package com.artacademy.learning.comment;

import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "lesson_comments", indexes = {
		@Index(name = "ix_lesson_comments_lesson_created", columnList = "lesson_id, created_at"),
		@Index(name = "ix_lesson_comments_parent", columnList = "parent_id"),
		@Index(name = "ix_lesson_comments_course", columnList = "course_id"),
		@Index(name = "ix_lesson_comments_author", columnList = "author_user_id") })
@Getter
@Setter
public class LessonComment {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@Column(name = "lesson_id", nullable = false) private Long lessonId;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "lesson_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_lesson_comments_lesson"))
	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE) private CourseLesson lessonRef;
	@Column(name = "course_id", nullable = false) private Long courseId;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "course_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_lesson_comments_course"))
	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE) private Course courseRef;
	@Column(name = "author_user_id", nullable = false) private Integer authorUserId;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "author_user_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_lesson_comments_author"))
	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE) private User authorRef;
	@Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "author_role", nullable = false, length = 20) private Role authorRole;
	@Column(name = "parent_id") private Long parentId;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_lesson_comments_parent"))
	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE) private LessonComment parentRef;
	@Column(nullable = false, length = 2000) private String body;
	@Column(name = "created_at", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
}
