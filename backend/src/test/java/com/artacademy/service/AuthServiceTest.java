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
    private EmailService emailService;

    @Mock
    private AuthenticationManager authenticationManager;

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
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any())).thenReturn("refresh-token");
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
        when(jwtService.generateAccessToken(any())).thenReturn("token");
        when(jwtService.generateRefreshToken(any())).thenReturn("refresh");
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
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any())).thenReturn("refresh-token");
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
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));
        when(jwtService.generateAccessToken(any())).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(any())).thenReturn("new-refresh-token");
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
        assertThat(clientId).isEqualTo("4555221892-omq3ncgr2ib4frohuct3mhsv5rs02m1n.apps.googleusercontent.com");
    }
}
