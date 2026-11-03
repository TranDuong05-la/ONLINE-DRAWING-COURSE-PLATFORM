package com.artacademy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

import com.drawingcourse.backend.config.CourseOnlyCheckoutConfiguration;
import com.drawingcourse.backend.config.CourseOnlyCartConfiguration;
import com.drawingcourse.backend.controller.CourseOnlyCheckoutController;
import com.drawingcourse.backend.controller.CourseOnlyCheckoutExceptionHandler;
import com.drawingcourse.backend.controller.CartApiExceptionHandler;
import com.drawingcourse.backend.controller.CartController;
import com.drawingcourse.backend.controller.CourseOnlyCartController;
import com.drawingcourse.backend.service.AuthenticatedCartCurrentUser;

// Member C's certificate and notification mail is dispatched asynchronously after commit.
@SpringBootApplication(scanBasePackages = "com.artacademy")
@EnableAsync
@EntityScan(basePackages = {"com.artacademy.entity", "com.drawingcourse.backend.entity",
        "com.artacademy.course", "com.artacademy.bundle", "com.artacademy.product", "com.artacademy.learning"})
@EnableJpaRepositories(basePackages = {"com.artacademy.repository", "com.drawingcourse.backend.repository",
        "com.artacademy.course", "com.artacademy.bundle", "com.artacademy.product", "com.artacademy.learning"})
@Import({CourseOnlyCheckoutConfiguration.class, CourseOnlyCartConfiguration.class,
        CartController.class, CourseOnlyCartController.class, CartApiExceptionHandler.class,
        CourseOnlyCheckoutController.class, CourseOnlyCheckoutExceptionHandler.class,
        AuthenticatedCartCurrentUser.class})
public class ArtAcademyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArtAcademyApplication.class, args);
    }
}
