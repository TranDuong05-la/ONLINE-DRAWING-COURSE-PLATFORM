package com.artacademy.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseModule;
import com.artacademy.entity.User;
import com.artacademy.entity.UserProfile;
import com.artacademy.learning.claim.CourseClaim;

/**
 * Bảo vệ hồi quy cho V12__learning.sql khi chưa có MySQL: chạy V11 (bảng của B mà C tham chiếu) và V12 trên H2
 * chế độ MySQL (bảng users/user_profiles tối giản thay cho V1), rồi đối chiếu từng bảng/cột của entity phần C.
 * Chỉ kiểm tra tên bảng và cột; kiểm tra đầy đủ kiểu dữ liệu/ràng buộc vẫn cần chạy trên MySQL thật.
 */
class LearningMigrationH2Test {

	@Test
	void v12CreatesEveryTableAndColumnMappedByLearningEntities() throws Exception {
		String url = "jdbc:h2:mem:v12_learning_check;MODE=MySQL;DB_CLOSE_DELAY=-1";
		DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
		try (Connection c = dataSource.getConnection()) {
			c.createStatement().execute("CREATE TABLE users (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY)");
			c.createStatement().execute("CREATE TABLE user_profiles (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY)");
		}
		ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
				new ClassPathResource("db/migration/V11__course_catalog.sql"),
				new ClassPathResource("db/migration/V12__learning.sql"),
				new ClassPathResource("db/migration/V13__learning_notifications.sql"));
		populator.execute(dataSource);

		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.applySetting("hibernate.connection.url", url)
				.applySetting("hibernate.connection.username", "sa")
				.applySetting("hibernate.connection.password", "")
				.applySetting("hibernate.physical_naming_strategy",
						"org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
				.build();
		Metadata metadata = new MetadataSources(registry)
				.addAnnotatedClass(User.class).addAnnotatedClass(UserProfile.class)
				.addAnnotatedClass(Course.class).addAnnotatedClass(CourseModule.class)
				.addAnnotatedClass(CourseLesson.class)
				.addAnnotatedClass(Enrollment.class).addAnnotatedClass(CourseClaim.class)
				.addAnnotatedClass(LessonProgress.class).addAnnotatedClass(Assignment.class)
				.addAnnotatedClass(AssignmentSubmission.class).addAnnotatedClass(Certificate.class)
				.addAnnotatedClass(com.artacademy.learning.notification.LearningNotification.class)
				.buildMetadata();

		List<String> learningTables = List.of("user_course_enrollments", "course_claims", "user_lesson_progress",
				"assignments", "assignment_submissions", "certificates", "learning_notifications");
		int checked = 0;
		try (Connection connection = dataSource.getConnection()) {
			for (PersistentClass entity : metadata.getEntityBindings()) {
				String table = entity.getTable().getName();
				if (!learningTables.contains(table)) {
					continue;
				}
				Set<String> actual = columnsOf(connection, table);
				assertThat(actual).as("bảng %s phải tồn tại trong V12", table).isNotEmpty();
				Set<String> expected = new HashSet<>();
				entity.getTable().getColumns().forEach(col -> expected.add(col.getName().toLowerCase(Locale.ROOT)));
				assertThat(actual).as("cột của bảng %s", table).containsAll(expected);
				assertThat(expected).as("V12 không có cột thừa ở bảng %s", table).containsAll(actual);
				checked++;
			}
		}
		assertThat(checked).isEqualTo(learningTables.size());
	}

	private static Set<String> columnsOf(Connection connection, String table) throws Exception {
		Set<String> columns = new HashSet<>();
		try (ResultSet rs = connection.getMetaData().getColumns(null, null, table.toUpperCase(Locale.ROOT), null)) {
			while (rs.next()) {
				columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
			}
		}
		return columns;
	}
}
