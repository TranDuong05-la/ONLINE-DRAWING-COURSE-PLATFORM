package com.drawingcourse.backend.service;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.CartItemJpaEntity;
import com.drawingcourse.backend.entity.CartJpaEntity;
import com.drawingcourse.backend.repository.CartItemRepository;
import com.drawingcourse.backend.repository.CartRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class CartPersistenceBootstrapIntegrationTest {
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private CartRepository carts;
    @Autowired private CartItemRepository items;
    @Autowired private CartCurrentUser currentUser;

    @Test
    void cartEntitiesRepositoriesAndIdentityAdapterAreRegisteredOnIsolatedH2() {
        assertThat(entityManagerFactory.getMetamodel().entity(CartJpaEntity.class)).isNotNull();
        assertThat(entityManagerFactory.getMetamodel().entity(CartItemJpaEntity.class)).isNotNull();
        assertThat(carts).isNotNull();
        assertThat(items).isNotNull();
        assertThat(currentUser).isInstanceOf(AuthenticatedCartCurrentUser.class);
    }
}
