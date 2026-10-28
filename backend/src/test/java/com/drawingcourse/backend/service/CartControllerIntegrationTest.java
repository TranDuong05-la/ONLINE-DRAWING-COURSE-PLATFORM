package com.drawingcourse.backend.service;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.controller.CartConflictException;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.CartJpaEntity;
import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.repository.CartItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = "app.cart.api-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CartControllerIntegrationTest.CartApiTestConfiguration.class)
class CartControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CustomUserDetailsService userDetails;
    @Autowired JwtService jwt;
    @Autowired CartRepository carts;
    @Autowired CartItemRepository cartItems;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired TestCartFixtures fixtures;

    @BeforeEach void resetFixtures() { fixtures.reset(); }

    @Test void everyRouteRequiresJwtAuthentication() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":1}")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/cart/items/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/cart/items/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/cart")).andExpect(status().isUnauthorized());
    }

    @Test void authenticatedUserOwnsCartAndClientIdentityOrPriceIsIgnored() throws Exception {
        User owner = createUser();
        User other = createUser();
        mvc.perform(post("/api/cart/items").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":2,\"userId\":" + other.getId() + ",\"price\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPriceVnd").value(2500))
                .andExpect(jsonPath("$.data.subtotalVnd").value(5000)).andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(get("/api/cart").header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.revision").value(0));
        assertThat(carts.findByUserId(owner.getId())).isPresent();
        assertThat(carts.findByUserId(other.getId())).isEmpty();
    }

    @Test void addPatchGetRemoveAndClearUseApiResponseAndHttp200() throws Exception {
        User owner = createUser();
        String auth = bearer(owner);
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.canCheckout").value(true))
                .andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].quantity").value(4))
                .andExpect(jsonPath("$.data.subtotalVnd").value(10_000)).andExpect(jsonPath("$.data.revision").value(2));
        Integer itemId = findCartItemId(owner.getId());
        mvc.perform(patch("/api/cart/items/{id}", itemId).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].quantity").value(3)).andExpect(jsonPath("$.data.subtotalVnd").value(7500))
                .andExpect(jsonPath("$.data.revision").value(3));
        mvc.perform(get("/api/cart").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(delete("/api/cart/items/{id}", itemId).header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(delete("/api/cart").header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.canCheckout").value(false))
                .andExpect(jsonPath("$.data.revision").value(4));
    }

    @Test void failedMutationRollsBackBothItemAndRevisionWrites() throws Exception {
        User owner = createUser();
        var cart = new CartJpaEntity(owner.getId());
        cart.addItem(CartItemType.PRODUCT, 1, 1);
        ReflectionTestUtils.setField(cart, "revision", Long.MAX_VALUE);
        carts.saveAndFlush(cart);
        Integer itemId = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status ->
                carts.findByUserId(owner.getId()).orElseThrow().getItems().getFirst().getId());

        mvc.perform(patch("/api/cart/items/{id}", itemId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isConflict());

        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
            var persisted = carts.findByUserId(owner.getId()).orElseThrow();
            assertThat(persisted.getRevision()).isEqualTo(Long.MAX_VALUE);
            assertThat(cartItems.findByIdAndCart_IdAndCart_UserId(itemId, persisted.getId(), owner.getId()))
                    .get().extracting(item -> item.getQuantity()).isEqualTo(1);
            return null;
        });
    }

    @Test void concurrentMutationsSerializeQuantityAndRevisionInTheDatabase() throws Exception {
        User owner = createUser();
        String auth = bearer(owner);
        int operations = 8;
        try (var pool = Executors.newFixedThreadPool(operations)) {
            var requests = java.util.stream.IntStream.range(0, operations).mapToObj(i ->
                    CompletableFuture.runAsync(() -> {
                        try {
                            mvc.perform(post("/api/cart/items").header("Authorization", auth)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":1}"))
                                    .andExpect(status().isOk());
                        } catch (Exception failure) {
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    }, pool)).toList();
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        }
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
            var persisted = carts.findByUserId(owner.getId()).orElseThrow();
            assertThat(persisted.getRevision()).isEqualTo(operations);
            assertThat(persisted.getItems()).hasSize(1);
            assertThat(persisted.getItems().getFirst().getQuantity()).isEqualTo(operations);
            return null;
        });
    }

    @Test void invalidBodiesAndPathIdsReturn400() throws Exception {
        User owner = createUser(); String auth = bearer(owner);
        for (String body : new String[]{"{}", "{\"itemType\":\"COURSE\",\"itemId\":1,\"quantity\":2}",
                "{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":0}", "{\"itemType\":\"BUNDLE\",\"itemId\":1,\"quantity\":1}", "not-json"}) {
            mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/cart/items/1").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/cart/items/0").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/cart/items/-3").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isBadRequest());
    }

    @Test void foreignItemAndMissingItemAreIndistinguishable404s() throws Exception {
        User owner = createUser(); User other = createUser();
        mvc.perform(post("/api/cart/items").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":1}")).andExpect(status().isOk());
        Integer id = findCartItemId(owner.getId());
        String foreign = mvc.perform(delete("/api/cart/items/{id}", id).header("Authorization", bearer(other))).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(delete("/api/cart/items/{id}", id + 1000000).header("Authorization", bearer(owner))).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreign).contains("Cart item not found"); assertThat(missing).contains("Cart item not found");
    }

    @Test void duplicateCourseIsIdempotentButNewOwnedOrPendingCourseConflicts() throws Exception {
        User owner = createUser(); String auth = bearer(owner);
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"COURSE\",\"itemId\":7,\"quantity\":1}")).andExpect(status().isOk());
        fixtures.rejectedCourseIds.add(7);
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"COURSE\",\"itemId\":7,\"quantity\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.revision").value(1));
        for (int rejected : new int[]{88, 89, 409}) {
            mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"itemType\":\"COURSE\",\"itemId\":" + rejected + ",\"quantity\":1}"))
                    .andExpect(status().isConflict());
        }
    }

    @Test void providerFailureIs503AndUnavailableLineCanBeRemovedButNotUpdated() throws Exception {
        User owner = createUser(); String auth = bearer(owner);
        fixtures.catalogUnavailable = true;
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":1}")).andExpect(status().isServiceUnavailable());
        fixtures.catalogUnavailable = false;
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"PRODUCT\",\"itemId\":404,\"quantity\":1}")).andExpect(status().isOk());
        Integer id = findCartItemId(owner.getId());
        fixtures.unavailableProducts.add(404);
        mvc.perform(get("/api/cart").header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availabilityStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].unitPriceVnd").doesNotExist())
                .andExpect(jsonPath("$.data.subtotalVnd").value(0)).andExpect(jsonPath("$.data.canCheckout").value(false));
        mvc.perform(patch("/api/cart/items/{id}", id).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/cart/items/{id}", id).header("Authorization", auth)).andExpect(status().isOk());
    }

    @Test void outOfStockProductsRemainAndDoNotContributeToSubtotalOrReserveStock() throws Exception {
        User owner = createUser();
        mvc.perform(post("/api/cart/items").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"PRODUCT\",\"itemId\":77,\"quantity\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].availabilityStatus").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3)).andExpect(jsonPath("$.data.subtotalVnd").value(0))
                .andExpect(jsonPath("$.data.hasUnavailableItems").value(true)).andExpect(jsonPath("$.data.canCheckout").value(false));
        assertThat(fixtures.stockReads).isPositive(); assertThat(fixtures.stockReservations).isZero();
    }

    private User createUser() {
        return users.saveAndFlush(User.builder().fullName("Cart API Test").email("cart-api-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-hash").role(Role.USER).isActive(true).build());
    }
    private String bearer(User user) { return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail())); }
    private Integer findCartItemId(Integer userId) {
        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status ->
                carts.findByUserId(userId).orElseThrow().getItems().getFirst().getId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.cart.api-enabled", havingValue = "true")
    static class CartApiTestConfiguration {
        @Bean TestCartFixtures cartTestFixtures() { return new TestCartFixtures(); }
        @Bean CartCatalog<Integer, Integer> cartCatalog(TestCartFixtures f) {
            return (user, item) -> {
                if (f.catalogUnavailable) throw new CartCatalogUnavailableException("test provider unavailable");
                if (item.getItemType() == CartItemType.COURSE && (f.rejectedCourseIds.contains(item.getItemId())
                        || item.getItemId() == 88 || item.getItemId() == 89 || item.getItemId() == 409)) {
                    throw new CartConflictException("Course cannot be added");
                }
                if (item.getItemType() == CartItemType.PRODUCT && f.unavailableProducts.contains(item.getItemId())) throw new CartConflictException("Product is unavailable");
            };
        }
        @Bean CartApplicationService cartApplicationService(CartRepository carts,
                com.drawingcourse.backend.repository.CartItemRepository items, CartCurrentUser current,
                CartCatalog<Integer, Integer> catalog, org.springframework.transaction.PlatformTransactionManager tx) {
            return new CartApplicationService(carts, items, current, catalog, tx);
        }
        @Bean CourseCartCatalogProvider courseCartCatalogProvider() {
            return id -> Optional.of(new CourseCartCatalogProvider.CourseListing(id, "Course " + id, 50_000, true));
        }
        @Bean CourseOwnershipProvider courseOwnershipProvider(TestCartFixtures f) {
            return (user, course) -> f.rejectedCourseIds.contains(course) && course == 88;
        }
        @Bean @Primary ActivePendingCoursePurchaseProvider pendingCourseProvider(TestCartFixtures f) {
            return (user, course, at) -> f.rejectedCourseIds.contains(course) && course == 89;
        }
        @Bean ProductCartCatalogProvider productCartCatalogProvider(TestCartFixtures f) {
            return id -> f.unavailableProducts.contains(id) ? Optional.empty()
                    : Optional.of(new ProductCartCatalogProvider.ProductListing(id, "Product " + id, 2_500, true));
        }
        @Bean ProductStockAvailabilityProvider productStockAvailabilityProvider(TestCartFixtures f) {
            return id -> { f.stockReads++; return id == 77 ? 0 : 100; };
        }
        @Bean CartAvailabilityProjector cartAvailabilityProjector(CourseCartCatalogProvider courses,
                CourseOwnershipProvider ownership, ActivePendingCoursePurchaseProvider pending,
                ProductCartCatalogProvider products, ProductStockAvailabilityProvider stock) {
            return new CartAvailabilityProjector(courses, ownership, pending, products, stock,
                    Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC));
        }
    }
    static class TestCartFixtures {
        final Set<Integer> rejectedCourseIds = ConcurrentHashMap.newKeySet();
        final Set<Integer> unavailableProducts = ConcurrentHashMap.newKeySet();
        volatile boolean catalogUnavailable;
        volatile int stockReads;
        volatile int stockReservations;
        void reset() { rejectedCourseIds.clear(); unavailableProducts.clear(); catalogUnavailable = false; stockReads = 0; stockReservations = 0; }
    }
}
