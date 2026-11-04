package com.drawingcourse.backend.config;

import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.integration.CourseSellableService;
import com.artacademy.learning.claim.CourseClaimService;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.repository.CartItemRepository;
import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.service.*;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicitly gated real Course + Product Cart composition; no inventory mutation happens on Cart operations. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.cart.api-enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${app.cart.course-only.api-enabled:false}' != 'true'")
public class GeneralCartConfiguration {
    // Ứng dụng có sẵn nhiều bean cùng kiểu (của C và D): chỉ rõ bean của giỏ hàng chung.
    @Bean CourseCartCatalogProvider generalCourseCartCatalog(CourseSellableService courses, CourseKitRepository kits) {
        return new CourseSellableCartCatalogAdapter(courses, kits);
    }
    @Bean CourseOwnershipProvider generalCartCourseOwnership(CourseClaimService claims) { return claims::isOwnedBy; }
    @Bean ActivePendingCoursePurchaseProvider generalCartPendingCourses(CourseClaimService claims) {
        return claims::hasActivePendingPurchase;
    }
    @Bean CartCatalog<Integer, Integer> generalCartCatalog(CourseCartCatalogProvider courses,
            @Qualifier("generalCartCourseOwnership") CourseOwnershipProvider ownership,
            @Qualifier("generalCartPendingCourses") ActivePendingCoursePurchaseProvider pending,
            ProductCartCatalogProvider products) {
        return new GeneralCartCatalogPolicy(courses, ownership, pending, products, Clock.systemUTC());
    }
    @Bean CartApplicationService generalCartApplicationService(CartRepository carts, CartItemRepository items,
            AuthenticatedCartCurrentUser currentUser, CartCatalog<Integer, Integer> generalCartCatalog,
            PlatformTransactionManager transactions) {
        return new CartApplicationService(carts, items, currentUser, generalCartCatalog, transactions,
                Set.of(CartItemType.COURSE, CartItemType.PRODUCT));
    }
    @Bean CartAvailabilityProjector generalCartAvailabilityProjector(CourseCartCatalogProvider courses,
            @Qualifier("generalCartCourseOwnership") CourseOwnershipProvider ownership,
            @Qualifier("generalCartPendingCourses") ActivePendingCoursePurchaseProvider pending,
            ProductCartCatalogProvider products, ProductStockAvailabilityProvider stock) {
        return new CartAvailabilityProjector(courses, ownership, pending, products, stock, Clock.systemUTC());
    }
}
