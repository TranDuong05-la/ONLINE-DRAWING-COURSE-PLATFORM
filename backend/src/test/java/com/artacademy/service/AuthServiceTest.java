package com.artacademy.service;

import com.artacademy.dto.*;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.exception.DuplicateResourceException;
import com.artacademy.exception.UnauthorizedException;
import com.artacademy.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.artacademy.repository.UserAddressRepository addressRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private com.artacademy.repository.UserProfileRepository userProfileRepository;

    @Mock
    private com.artacademy.repository.UserRoleHistoryRepository userRoleHistoryRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock

    private GoogleTokenInfoClient googleTokenInfoClient;

    private CloudinaryService cloudinaryService;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;
    private User testUser;

    @BeforeEach
    void setUp() {
        registerRequest = RegisterRequest.builder()
                .fullName("Nguyen Van A")
                .email("user@example.com")
                .password("StrongPassword123")
                .phone("0900000000")
                .build();

        loginRequest = LoginRequest.builder()
                .email("user@example.com")
                .password("StrongPassword123")
                .build();

        testUser = User.builder()
                .id(1)
                .fullName("Nguyen Van A")
                .email("user@example.com")
                .passwordHash("$2a$10$hashedpassword")
                .phone("0900000000")
                .role(Role.USER)
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        org.springframework.test.util.ReflectionTestUtils.setField(
                authService,
                "googleClientId",
                "631102057997-4b3jmabbdrrv57dsp1rbuin9vjb9onm1.apps.googleusercontent.com"
        );
    }

    // =====================
    // REGISTRATION TESTS
    // =====================

    @Test
    @DisplayName("Register: Success - creates new user with hashed password")
    void register_success() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashedpassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(jwtService.generateAccessToken(any(), any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(), any())).thenReturn("refresh-token");
        when(jwtService.getAccessExpirationMs()).thenReturn(900000L);

        AuthResponse response = authService.register(registerRequest);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getEmail()).isEqualTo("user@example.com");
        assertThat(response.getUser().getRole()).isEqualTo(Role.USER);

        verify(passwordEncoder).encode("StrongPassword123");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Register: Password is stored as BCrypt hash, not plaintext")
    void register_passwordIsHashed() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode("StrongPassword123")).thenReturn("$2a$10$bcryptedHash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$bcryptedHash");
            assertThat(saved.getPasswordHash()).isNotEqualTo("StrongPassword123");
            saved.setId(1);
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());
            return saved;
        });
        when(jwtService.generateAccessToken(any(), any())).thenReturn("token");
        when(jwtService.generateRefreshToken(any(), any())).thenReturn("refresh");
        when(jwtService.getAccessExpirationMs()).thenReturn(900000L);

        authService.register(registerRequest);

        verify(passwordEncoder).encode("StrongPassword123");
    }

    @Test
    @DisplayName("Register: Reject duplicate email")
    void register_duplicateEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest))
                .isInstanceOf(DuplicateResourceException.class);

        verify(userRepository, never()).save(any());
    }

    // =====================
    // LOGIN TESTS
    // =====================

    @Test
    @DisplayName("Login: Success with correct credentials")
    void login_success() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(jwtService.generateAccessToken(any(), any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(), any())).thenReturn("refresh-token");
        when(jwtService.getAccessExpirationMs()).thenReturn(900000L);

        AuthResponse response = authService.login(loginRequest);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getUser().getEmail()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("Login: Reject wrong password")
    void login_wrongPassword() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("Login: Reject deactivated account")
    void login_deactivatedAccount() {
        testUser.setIsActive(false);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    @DisplayName("Login: Reject non-existent email")
    void login_nonExistentEmail() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(BadCredentialsException.class);
    }

    // =====================
    // REFRESH TOKEN TESTS (STATELESS JWT)
    // =====================

    @Test
    @DisplayName("Refresh: Success with valid stateless refresh token")
    void refresh_success() {
        when(jwtService.isRefreshTokenValid("valid-refresh-token")).thenReturn(true);
        when(jwtService.extractUsername("valid-refresh-token")).thenReturn("user@example.com");
        when(jwtService.extractTokenVersion("valid-refresh-token")).thenReturn(1);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));
        when(jwtService.generateAccessToken(any(), any())).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(any(), any())).thenReturn("new-refresh-token");
        when(jwtService.getAccessExpirationMs()).thenReturn(900000L);

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("valid-refresh-token")
                .build();

        AuthResponse response = authService.refresh(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
        assertThat(response.getUser().getEmail()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("Refresh: Invalid or expired refresh token is rejected")
    void refresh_invalidOrExpiredToken() {
        when(jwtService.isRefreshTokenValid("invalid-refresh-token")).thenReturn(false);

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("invalid-refresh-token")
                .build();

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("revoked or expired");
    }

    @Test
    @DisplayName("Refresh: Blank refresh token is rejected")
    void refresh_blankToken() {
        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("")
                .build();

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("required");
    }

    // =====================
    // GOOGLE SIGN-IN TESTS
    // =====================

    @Test
    @DisplayName("Google Login: Null or empty ID token is rejected")
    void googleLogin_emptyToken_throwsException() {
        GoogleLoginRequest request = GoogleLoginRequest.builder().idToken("").build();

        assertThatThrownBy(() -> authService.loginWithGoogle(request))
                .isInstanceOf(com.artacademy.exception.BusinessException.class)
                .hasMessageContaining("bắt buộc");
    }

    @Test
    @DisplayName("Google Login: Invalid token signature/verifier throws UnauthorizedException")
    void googleLogin_invalidToken_throwsUnauthorized() {
        when(googleTokenInfoClient.fetch(anyString()))
                .thenThrow(new org.springframework.web.client.RestClientException("deterministic test failure"));
        GoogleLoginRequest request = GoogleLoginRequest.builder()
                .idToken("invalid.jwt.token")
                .build();

        assertThatThrownBy(() -> authService.loginWithGoogle(request))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("Google Login: Default Client ID matches user configuration")
    void googleLogin_clientId_matchesConfig() {
        String clientId = authService.getGoogleClientId();
        assertThat(clientId).isEqualTo("631102057997-4b3jmabbdrrv57dsp1rbuin9vjb9onm1.apps.googleusercontent.com");
    }

    @Test
    @DisplayName("Google Login: New user registers automatically, creates learner profile and returns JWT tokens")
    void googleLogin_newUser_success() {
        AuthService spyAuthService = org.mockito.Mockito.spy(authService);
        org.mockito.Mockito.doReturn(new AuthService.GoogleUserInfo(
                "google-sub-12345",
                "googleuser@example.com",
                "Google User",
                "https://avatar.url/pic.jpg"
        )).when(spyAuthService).verifyGoogleIdToken("valid.mock.token");

        when(userRepository.findByGoogleId("google-sub-12345")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("googleuser@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encodeduuid");

        User savedUser = User.builder()
                .id(10)
                .email("googleuser@example.com")
                .fullName("Google User")
                .passwordHash("$2a$10$encodeduuid")
                .googleId("google-sub-12345")
                .avatarUrl("https://avatar.url/pic.jpg")
                .role(Role.USER)
                .isActive(true)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateAccessToken(any(), any())).thenReturn("google-jwt-access-token");
        when(jwtService.generateRefreshToken(any(), any())).thenReturn("google-jwt-refresh-token");
        when(jwtService.getAccessExpirationMs()).thenReturn(900000L);

        GoogleLoginRequest request = GoogleLoginRequest.builder()
                .idToken("valid.mock.token")
                .build();

        AuthResponse response = spyAuthService.loginWithGoogle(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("google-jwt-access-token");
        assertThat(response.getRefreshToken()).isEqualTo("google-jwt-refresh-token");
        assertThat(response.getUser().getEmail()).isEqualTo("googleuser@example.com");
        assertThat(response.getUser().getRole()).isEqualTo(Role.USER);

        verify(userProfileRepository).save(any());
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Google Login: Existing user by Google Subject ID logs in directly")
    void googleLogin_existingGoogleUser_success() {
        AuthService spyAuthService = org.mockito.Mockito.spy(authService);
        org.mockito.Mockito.doReturn(new AuthService.GoogleUserInfo(
                "google-sub-existing",
                "existing@example.com",
                "Existing User",
                null
        )).when(spyAuthService).verifyGoogleIdToken("valid.mock.token");

        User existingUser = User.builder()
                .id(5)
                .email("existing@example.com")
                .fullName("Existing User")
                .passwordHash("$2a$10$hashedpassword")
                .googleId("google-sub-existing")
                .role(Role.USER)
                .isActive(true)
                .build();
        when(userRepository.findByGoogleId("google-sub-existing")).thenReturn(Optional.of(existingUser));
        when(jwtService.generateAccessToken(any(), any())).thenReturn("existing-jwt-access");
        when(jwtService.generateRefreshToken(any(), any())).thenReturn("existing-jwt-refresh");
        when(jwtService.getAccessExpirationMs()).thenReturn(900000L);

        GoogleLoginRequest request = GoogleLoginRequest.builder().idToken("valid.mock.token").build();

        AuthResponse response = spyAuthService.loginWithGoogle(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("existing-jwt-access");
        assertThat(response.getUser().getEmail()).isEqualTo("existing@example.com");
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    @DisplayName("Google Login: Email already registered with password links Google ID seamlessly")
    void googleLogin_emailRegisteredWithPassword_linksAccount() {
        AuthService spyAuthService = org.mockito.Mockito.spy(authService);
        org.mockito.Mockito.doReturn(new AuthService.GoogleUserInfo(
                "google-sub-linked",
                "passworduser@example.com",
                "Linked User",
                "https://example.com/photo.jpg"
        )).when(spyAuthService).verifyGoogleIdToken("valid.mock.token");

        User passwordUser = User.builder()
                .id(6)
                .email("passworduser@example.com")
                .fullName("Linked User")
                .googleId(null) // Registered via password
                .role(Role.USER)
                .isActive(true)
                .build();
        when(userRepository.findByGoogleId("google-sub-linked")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("passworduser@example.com")).thenReturn(Optional.of(passwordUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any(), any())).thenReturn("linked-access-token");
        when(jwtService.generateRefreshToken(any(), any())).thenReturn("linked-refresh-token");

        GoogleLoginRequest request = GoogleLoginRequest.builder().idToken("valid.mock.token").build();

        AuthResponse response = spyAuthService.loginWithGoogle(request);

        assertThat(response.getAccessToken()).isEqualTo("linked-access-token");
        assertThat(passwordUser.getGoogleId()).isEqualTo("google-sub-linked");
        verify(userRepository).save(passwordUser);
    }

    @Test
    @DisplayName("Google Login: Email already linked with different Google ID throws exception")
    void googleLogin_emailLinkedWithDifferentGoogleId_throwsException() {
        AuthService spyAuthService = org.mockito.Mockito.spy(authService);
        org.mockito.Mockito.doReturn(new AuthService.GoogleUserInfo(
                "google-sub-new",
                "otheruser@example.com",
                "Other User",
                null
        )).when(spyAuthService).verifyGoogleIdToken("valid.mock.token");

        User alreadyLinkedUser = User.builder()
                .id(7)
                .email("otheruser@example.com")
                .googleId("google-sub-original")
                .role(Role.USER)
                .isActive(true)
                .build();
        when(userRepository.findByGoogleId("google-sub-new")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("otheruser@example.com")).thenReturn(Optional.of(alreadyLinkedUser));

        GoogleLoginRequest request = GoogleLoginRequest.builder().idToken("valid.mock.token").build();

        assertThatThrownBy(() -> spyAuthService.loginWithGoogle(request))
                .isInstanceOf(com.artacademy.exception.BusinessException.class)
                .hasMessageContaining("đã được liên kết với một tài khoản Google khác");
    }

    @Test
    @DisplayName("Google Login: Locked or inactive account is rejected")
    void googleLogin_lockedAccount_throwsDisabledException() {
        AuthService spyAuthService = org.mockito.Mockito.spy(authService);
        org.mockito.Mockito.doReturn(new AuthService.GoogleUserInfo(
                "google-sub-locked",
                "locked@example.com",
                "Locked User",
                null
        )).when(spyAuthService).verifyGoogleIdToken("valid.mock.token");

        User lockedUser = User.builder()
                .id(7)
                .email("locked@example.com")
                .googleId("google-sub-locked")
                .role(Role.USER)
                .isActive(false) // Locked account
                .build();
        when(userRepository.findByGoogleId("google-sub-locked")).thenReturn(Optional.of(lockedUser));

        GoogleLoginRequest request = GoogleLoginRequest.builder().idToken("valid.mock.token").build();

        assertThatThrownBy(() -> spyAuthService.loginWithGoogle(request))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class)
                .hasMessageContaining("khóa");
    }

    // ==========================================
    // RBAC & ADMIN USER MANAGEMENT TESTS
    // ==========================================

    @Test
    @DisplayName("Admin Update Role: Success - changes role, updates token version and records history")
    void updateUserRole_success() {
        User admin = User.builder().id(99).email("admin@artnest.com").role(Role.ADMIN).build();
        User target = User.builder().id(10).email("user@artnest.com").role(Role.USER).tokenVersion(1).build();

        when(userRepository.findByEmail("admin@artnest.com")).thenReturn(Optional.of(admin));
        when(userRepository.findById(10)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateUserRoleRequest request = UpdateUserRoleRequest.builder()
                .role(Role.INSTRUCTOR)
                .reason("Promoted to instructor")
                .build();

        UserResponse response = authService.updateUserRole("admin@artnest.com", 10, request);

        assertThat(response).isNotNull();
        assertThat(response.getRole()).isEqualTo(Role.INSTRUCTOR);
        assertThat(target.getTokenVersion()).isEqualTo(2);
        verify(userRoleHistoryRepository, times(1)).save(any(com.artacademy.entity.UserRoleHistory.class));
    }

    @Test
    @DisplayName("Admin Update Role: Non-admin cannot update role - throws UnauthorizedException")
    void updateUserRole_nonAdmin_throwsUnauthorizedException() {
        User regularUser = User.builder().id(88).email("user@artnest.com").role(Role.USER).build();
        when(userRepository.findByEmail("user@artnest.com")).thenReturn(Optional.of(regularUser));

        UpdateUserRoleRequest request = UpdateUserRoleRequest.builder().role(Role.ADMIN).build();

        assertThatThrownBy(() -> authService.updateUserRole("user@artnest.com", 10, request))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("Admin Update Role: Cannot demote the last active admin")
    void updateUserRole_demoteLastAdmin_throwsBusinessException() {
        User admin = User.builder().id(99).email("admin@artnest.com").role(Role.ADMIN).build();
        User targetAdmin = User.builder().id(100).email("targetadmin@artnest.com").role(Role.ADMIN).build();

        when(userRepository.findByEmail("admin@artnest.com")).thenReturn(Optional.of(admin));
        when(userRepository.findById(100)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.countByRoleAndIsActive(Role.ADMIN, true)).thenReturn(1L);

        UpdateUserRoleRequest request = UpdateUserRoleRequest.builder().role(Role.USER).build();

        assertThatThrownBy(() -> authService.updateUserRole("admin@artnest.com", 100, request))
                .isInstanceOf(com.artacademy.exception.BusinessException.class)
                .hasMessageContaining("last active admin");
    }

    @Test
    @DisplayName("Admin Update Status: Cannot deactivate the last active admin")
    void updateUserStatus_deactivateLastAdmin_throwsBusinessException() {
        User targetAdmin = User.builder().id(100).email("targetadmin@artnest.com").role(Role.ADMIN).build();
        when(userRepository.findById(100)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.countByRoleAndIsActive(Role.ADMIN, true)).thenReturn(1L);

        assertThatThrownBy(() -> authService.updateUserStatus(100, false))
                .isInstanceOf(com.artacademy.exception.BusinessException.class)
                .hasMessageContaining("Cannot deactivate the last active admin account");
    }

    @Test
    @DisplayName("Admin Dashboard: Returns correct aggregated metrics")
    void getAdminDashboard_returnsAggregatedMetrics() {
        when(userRepository.count()).thenReturn(10L);
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(2L);
        when(userRepository.countByRole(Role.INSTRUCTOR)).thenReturn(3L);
        when(userRepository.countByRole(Role.STAFF)).thenReturn(1L);
        when(userRepository.countByIsActive(true)).thenReturn(8L);
        when(userRepository.countByIsActive(false)).thenReturn(2L);

        AdminDashboardResponse dashboard = authService.getAdminDashboard();

        assertThat(dashboard.getTotalUsers()).isEqualTo(10L);
        assertThat(dashboard.getTotalAdmins()).isEqualTo(2L);
        assertThat(dashboard.getTotalInstructors()).isEqualTo(3L);
        assertThat(dashboard.getTotalStaff()).isEqualTo(1L);
        assertThat(dashboard.getActiveUsers()).isEqualTo(8L);
        assertThat(dashboard.getInactiveUsers()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Update Avatar: Uploads image file to Cloudinary and saves user avatarUrl")
    void updateAvatar_success() {
        org.springframework.web.multipart.MultipartFile mockFile = org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
        String expectedUrl = "https://res.cloudinary.com/test/image/upload/v1/artacademy/avatars/avatar123.jpg";
        
        when(userRepository.findByEmail("user@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(cloudinaryService.uploadAvatar(mockFile)).thenReturn(expectedUrl);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.artacademy.dto.UserResponse response = authService.updateAvatar("user@example.com", mockFile);

        assertThat(response).isNotNull();
        assertThat(response.getAvatarUrl()).isEqualTo(expectedUrl);
        assertThat(testUser.getAvatarUrl()).isEqualTo(expectedUrl);
        verify(cloudinaryService).uploadAvatar(mockFile);
        verify(userRepository).save(testUser);
    }
}
