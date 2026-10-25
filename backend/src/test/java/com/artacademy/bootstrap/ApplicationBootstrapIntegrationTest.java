package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.config.DrawingCourseBackendComponentScan;
import com.artacademy.config.JwtAuthenticationFilter;
import com.artacademy.entity.User;
import com.artacademy.entity.UserAddress;
import com.artacademy.entity.UserProfile;
import com.artacademy.repository.UserAddressRepository;
import com.artacademy.repository.UserProfileRepository;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserIdResolver;
import com.artacademy.service.CustomUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationBootstrapIntegrationTest {
    @Autowired private org.springframework.context.ApplicationContext context;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private UserRepository users;
    @Autowired private UserAddressRepository addresses;
    @Autowired private UserProfileRepository profiles;

    @Test
    void aAuthenticationAndTrustedIdentityBeansRemainAvailable() {
        assertThat(context.getBean(ArtAcademyApplication.class)).isNotNull();
        assertThat(context.getBean(CustomUserDetailsService.class)).isNotNull();
        assertThat(context.getBean(JwtAuthenticationFilter.class)).isNotNull();
        assertThat(context.getBean(AuthenticatedUserIdResolver.class)).isNotNull();
        assertThat(context.getBean(UserRepository.class)).isSameAs(users);
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
    }

    @Test
    void aRepositoriesAndEntitiesAreExplicitlyDiscoveredOnH2() {
        assertThat(context.getBean(UserRepository.class)).isNotNull();
        assertThat(context.getBean(UserAddressRepository.class)).isSameAs(addresses);
        assertThat(context.getBean(UserProfileRepository.class)).isSameAs(profiles);
        var metamodel = entityManagerFactory.getMetamodel();
        assertThat(metamodel.entity(User.class)).isNotNull();
        assertThat(metamodel.entity(UserAddress.class)).isNotNull();
        assertThat(metamodel.entity(UserProfile.class)).isNotNull();
    }

    @Test
    void dPackageScanIsPreparedAndExcludesItsLegacyBootEntryPoint() {
        var configuration = context.getBean(DrawingCourseBackendComponentScan.class);
        assertThat(configuration).isNotNull();
        ComponentScan scan = DrawingCourseBackendComponentScan.class.getAnnotation(ComponentScan.class);
        assertThat(scan.basePackages()).containsExactly("com.drawingcourse.backend");
        assertThat(Arrays.stream(scan.excludeFilters()).anyMatch(filter ->
                filter.type() == FilterType.REGEX
                        && Arrays.asList(filter.pattern()).contains(
                                "com\\.drawingcourse\\.backend\\.BackendApplication"))).isTrue();
        assertThat(context.getBeansWithAnnotation(SpringBootConfiguration.class).keySet())
                .containsExactly("artAcademyApplication");
    }
}