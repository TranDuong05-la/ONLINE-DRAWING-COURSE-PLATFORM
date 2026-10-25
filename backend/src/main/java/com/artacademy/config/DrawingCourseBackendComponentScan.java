package com.artacademy.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/** Scans future D modules while preventing their legacy application class becoming a second Boot configuration. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
        basePackages = "com.drawingcourse.backend",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = DrawingCourseBackendComponentScan.LEGACY_APPLICATION_CLASS_PATTERN))
public class DrawingCourseBackendComponentScan {
    static final String LEGACY_APPLICATION_CLASS_PATTERN =
            "com\\.drawingcourse\\.backend\\.BackendApplication";
}