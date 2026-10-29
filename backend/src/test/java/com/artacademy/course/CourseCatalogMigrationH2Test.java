package com.artacademy.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.artacademy.bundle.BundleItem;
import com.artacademy.bundle.CourseBundle;
import com.artacademy.course.Course;
import com.artacademy.course.CourseIncludedKit;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseModule;
import com.artacademy.product.Product;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
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

/**
 * Bảo vệ hồi quy cho V8__course_catalog.sql khi chưa có MySQL để chạy thật: chạy V8 trên H2 (chế độ MySQL) rồi
 * đối chiếu từng bảng/cột mà entity phần B ánh xạ. Chỉ kiểm tra tên bảng và cột (không kiểm tra kiểu dữ liệu
 * hay ràng buộc); kiểm tra đầy đủ vẫn cần MySqlSchemaCompatibilityIT trên MySQL thử riêng.
 */
class CourseCatalogMigrationH2Test {

    @Test
    void migrationCreatesEveryTableAndColumnMappedByCatalogEntities() throws Exception {
        String url = "jdbc:h2:mem:v8_migration_check;MODE=MySQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__course_catalog.sql")).execute(dataSource);

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                // giống Spring Boot: thuộc tính camelCase -> cột snake_case khi entity không ghi tên cột
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        Metadata metadata = new MetadataSources(registry)
                .addAnnotatedClass(Product.class)
                .addAnnotatedClass(Course.class)
                .addAnnotatedClass(CourseModule.class)
                .addAnnotatedClass(CourseLesson.class)
                .addAnnotatedClass(CourseIncludedKit.class)
                .addAnnotatedClass(CourseBundle.class)
                .addAnnotatedClass(BundleItem.class)
                .buildMetadata();

        try (Connection connection = dataSource.getConnection()) {
            int checked = 0;
            for (PersistentClass entity : metadata.getEntityBindings()) {
                String table = entity.getTable().getName();
                Set<String> actual = columnsOf(connection, table);
                assertThat(actual).as("bảng %s phải tồn tại trong V8", table).isNotEmpty();

                Set<String> expected = new HashSet<>();
                entity.getTable().getColumns().forEach(c -> expected.add(c.getName().toLowerCase(Locale.ROOT)));
                assertThat(actual).as("cột của bảng %s", table).containsAll(expected);
                checked++;
            }
            assertThat(checked).isEqualTo(7);
        }
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
