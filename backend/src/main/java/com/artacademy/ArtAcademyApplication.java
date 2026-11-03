package com.artacademy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.drawingcourse.backend.config.CourseOnlyCheckoutConfiguration;

@SpringBootApplication(scanBasePackages = "com.artacademy")
@EntityScan(basePackages = {"com.artacademy.entity", "com.drawingcourse.backend.entity"})
@EnableJpaRepositories(basePackages = {"com.artacademy.repository", "com.drawingcourse.backend.repository"})
@Import(CourseOnlyCheckoutConfiguration.class)
public class ArtAcademyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArtAcademyApplication.class, args);
    }
}
