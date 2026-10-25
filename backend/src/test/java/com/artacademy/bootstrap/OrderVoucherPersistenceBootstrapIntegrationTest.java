package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.AuthenticatedOrderCurrentUser;
import com.drawingcourse.backend.service.OrderPersistenceService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class OrderVoucherPersistenceBootstrapIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private OrderShippingAddressRepository addresses;
    @Autowired private VoucherRepository vouchers;
    @Autowired private VoucherUserUsageRepository voucherUsers;
    @Autowired private VoucherClaimRepository voucherClaims;

    @Test void importsOrderAndVoucherMappingsWithoutCheckoutOrPaymentActivation() {
        var metamodel = entityManagerFactory.getMetamodel();
        assertThat(metamodel.entity(OrderJpaEntity.class)).isNotNull();
        assertThat(metamodel.entity(OrderItemJpaEntity.class)).isNotNull();
        assertThat(metamodel.entity(OrderShippingAddressJpaEntity.class)).isNotNull();
        assertThat(metamodel.entity(VoucherJpaEntity.class)).isNotNull();
        assertThat(metamodel.entity(VoucherUserUsageJpaEntity.class)).isNotNull();
        assertThat(metamodel.entity(VoucherClaimJpaEntity.class)).isNotNull();
        assertThat(orders).isNotNull();
        assertThat(orderItems).isNotNull();
        assertThat(addresses).isNotNull();
        assertThat(vouchers).isNotNull();
        assertThat(voucherUsers).isNotNull();
        assertThat(voucherClaims).isNotNull();
        assertThat(context.getBean(AuthenticatedOrderCurrentUser.class)).isNotNull();
        assertThat(context.getBeansOfType(OrderPersistenceService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ArtAcademyApplication.class)).containsExactly("artAcademyApplication");
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("vnpay")
                || name.toLowerCase().contains("paymentsettlement")
                || name.toLowerCase().contains("checkoutorchestrator")
                || name.toLowerCase().contains("voucherclaimapplication"));
    }
}
