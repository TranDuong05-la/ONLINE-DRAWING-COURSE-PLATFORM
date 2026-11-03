package com.artacademy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import com.drawingcourse.backend.config.CourseOnlyCheckoutConfiguration;

// Entity/repository của A, D (entity, repository, drawingcourse), B (course, bundle, product),
// C (learning) và E (supply). @EnableAsync: email chứng nhận (C) gửi ở luồng nền sau khi DB commit.
@SpringBootApplication(scanBasePackages = "com.artacademy")
@EnableAsync
@EntityScan(basePackages = {"com.artacademy.entity", "com.drawingcourse.backend.entity",
        "com.artacademy.course", "com.artacademy.bundle", "com.artacademy.product", "com.artacademy.learning",
        "com.artacademy.supply"})
@EnableJpaRepositories(basePackages = {"com.artacademy.repository", "com.drawingcourse.backend.repository",
        "com.artacademy.course", "com.artacademy.bundle", "com.artacademy.product", "com.artacademy.learning",
        "com.artacademy.supply"})
@Import(CourseOnlyCheckoutConfiguration.class)
public class ArtAcademyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArtAcademyApplication.class, args);
    }
}
