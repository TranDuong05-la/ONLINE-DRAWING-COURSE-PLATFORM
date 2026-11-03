package com.drawingcourse.backend.config;

import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.integration.CourseSellableService;
import com.artacademy.learning.claim.CourseClaimService;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.repository.CartItemRepository;
import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.service.ActivePendingCoursePurchaseProvider;
import com.drawingcourse.backend.service.CartApplicationService;
import com.drawingcourse.backend.service.CartCatalog;
import com.drawingcourse.backend.service.CourseCartCatalogProvider;
import com.drawingcourse.backend.service.CourseOnlyCartCatalogPolicy;
import com.drawingcourse.backend.service.CourseOnlyCartProjector;
import com.drawingcourse.backend.service.CourseOwnershipProvider;
import com.drawingcourse.backend.service.CourseSellableCartCatalogAdapter;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit composition for the gated D Course-only Cart API; it has no E/Product dependencies. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.cart.course-only.api-enabled", havingValue = "true")
public class CourseOnlyCartConfiguration {
    private static final String OWNERSHIP = "courseOnlyCartOwnership";
    private static final String PENDING = "courseOnlyCartPendingPurchases";

    @Bean
    CourseCartCatalogProvider courseOnlyCartCatalogProvider(CourseSellableService courses, CourseKitRepository kits) {
        return new CourseSellableCartCatalogAdapter(courses, kits);
    }

    @Bean(OWNERSHIP)
    CourseOwnershipProvider courseOnlyCartOwnership(CourseClaimService claims) {
        return claims::isOwnedBy;
    }

    @Bean(PENDING)
    ActivePendingCoursePurchaseProvider courseOnlyCartPendingPurchases(CourseClaimService claims) {
        return claims::hasActivePendingPurchase;
    }

    @Bean
    CartCatalog<Integer, Integer> courseOnlyCartCatalog(CourseCartCatalogProvider courses,
            @Qualifier(OWNERSHIP) CourseOwnershipProvider ownership,
            @Qualifier(PENDING) ActivePendingCoursePurchaseProvider pending) {
        return new CourseOnlyCartCatalogPolicy(courses, ownership, pending, Clock.systemUTC());
    }

    @Bean
    CartApplicationService courseOnlyCartApplicationService(CartRepository carts, CartItemRepository items,
            com.drawingcourse.backend.service.AuthenticatedCartCurrentUser currentUser,
            CartCatalog<Integer, Integer> courseOnlyCartCatalog,
            PlatformTransactionManager transactions) {
        return new CartApplicationService(carts, items, currentUser, courseOnlyCartCatalog, transactions,
                Set.of(CartItemType.COURSE));
    }

    @Bean
    CourseOnlyCartProjector courseOnlyCartProjector(CourseCartCatalogProvider courses,
            @Qualifier(OWNERSHIP) CourseOwnershipProvider ownership,
            @Qualifier(PENDING) ActivePendingCoursePurchaseProvider pending) {
        return new CourseOnlyCartProjector(courses, ownership, pending, Clock.systemUTC());
    }
}
