package com.artacademy.service;

import com.artacademy.dto.*;
import com.artacademy.entity.User;
import com.artacademy.entity.UserAddress;
import com.artacademy.enums.Role;
import com.artacademy.exception.BusinessException;
import com.artacademy.exception.DuplicateResourceException;
import com.artacademy.exception.ResourceNotFoundException;
import com.artacademy.exception.UnauthorizedException;
import com.artacademy.repository.UserAddressRepository;
import com.artacademy.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.entity.UserProfile;
import com.artacademy.enums.AgeGroup;
import com.artacademy.enums.ProfileType;
import com.artacademy.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Collections;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Value("${app.google.client-id:}")
    private String googleClientId;

    @Value("${app.google.client-secret:}")
    private String googleClientSecret;

    private final UserRepository userRepository;
    private final UserAddressRepository addressRepository;
    private final UserProfileRepository userProfileRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final Map<String, OtpData> passwordResetOtpCache = new ConcurrentHashMap<>();

    public static class OtpData {
        private final String code;
        private final LocalDateTime expiresAt;

        public OtpData(String code, LocalDateTime expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }

        public String getCode() {
            return code;
        }
    }

    public AuthService(UserRepository userRepository,
                       UserAddressRepository addressRepository,
                       UserProfileRepository userProfileRepository,
                       EmailService emailService,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.userProfileRepository = userProfileRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
    }

    // ==========================================
    // AUTHENTICATION OPERATIONS
    // ==========================================

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("User", "email", normalizedEmail);
        }

        User user = User.builder()
                .fullName(request.getFullName().trim())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .phone(request.getPhone())
                .role(Role.USER)
                .isActive(true)
                .build();

        user = userRepository.save(user);

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        // Check if user exists and is active before attempting authentication
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!user.getIsActive()) {
            throw new DisabledException("Account has been deactivated");
        }

        // Authenticate via Spring Security
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, request.getPassword())
        );

        if (!authentication.isAuthenticated()) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String token = request.getRefreshToken();
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Refresh token is required");
        }

        if (!jwtService.isRefreshTokenValid(token)) {
            throw new UnauthorizedException("Refresh token has been revoked or expired");
        }

        String email = jwtService.extractUsername(token);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        if (!user.getIsActive()) {
            throw new UnauthorizedException("Account has been deactivated");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        // Stateless JWT refresh token: client discards tokens upon logout
    }

    // ==========================================
    // USER PROFILE OPERATIONS
    // ==========================================

    public UserResponse getCurrentUser(String email) {
        User user = findUserByEmail(email);
        return mapToUserResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(String email, UpdateProfileRequest request) {
        User user = findUserByEmail(email);

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }

        user = userRepository.save(user);
        return mapToUserResponse(user);
    }

    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = findUserByEmail(email);

        // Verify current password
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Current password is incorrect");
        }

        // Validate new password confirmation
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("New password and confirm password do not match");
        }

        // Ensure new password differs from old
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("New password must be different from current password");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    public void sendPasswordResetOtp(ForgotPasswordRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            log.warn("Forgot password requested for non-existing email: {}", normalizedEmail);
            return;
        }

        // Generate 6-digit OTP code
        String otpCode = String.format("%06d", new SecureRandom().nextInt(1000000));
        passwordResetOtpCache.put(normalizedEmail, new OtpData(otpCode, LocalDateTime.now().plusMinutes(15)));

        // Send email with Thymeleaf template
        try {
            emailService.sendTemplateEmail(
                    user.getEmail(),
                    "Art Academy - Mã xác thực đặt lại mật khẩu",
                    "email/reset-password",
                    Map.of(
                            "fullName", user.getFullName() != null ? user.getFullName() : "Học viên",
                            "otpCode", otpCode,
                            "expireMinutes", 15
                    )
            );
            log.info("Password reset OTP sent to: {}", normalizedEmail);
        } catch (Exception e) {
            log.error("Failed to send password reset OTP to: {}", normalizedEmail, e);
            throw new BusinessException("Không thể gửi email lúc này. Vui lòng thử lại sau.");
        }
    }

    @Transactional
    public void resetPasswordWithOtp(ResetPasswordRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", normalizedEmail));

        OtpData otpData = passwordResetOtpCache.get(normalizedEmail);
        if (otpData == null || otpData.isExpired() || !otpData.getCode().equals(request.getOtp().trim())) {
            throw new BusinessException("Mã xác thực không hợp lệ hoặc đã hết hạn (hiệu lực 15 phút).");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("Mật khẩu mới và mật khẩu xác nhận không khớp.");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("Mật khẩu mới phải khác mật khẩu hiện tại.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Remove OTP from cache after successful reset
        passwordResetOtpCache.remove(normalizedEmail);
        log.info("Password successfully reset with OTP for user: {}", normalizedEmail);
    }

    private record GoogleUserInfo(String sub, String email, String name, String pictureUrl) {}

    private GoogleUserInfo verifyGoogleIdToken(String idTokenString) {
        if (idTokenString == null || idTokenString.trim().isEmpty()) {
            throw new BusinessException("Mã xác thực Google ID Token không được để trống.");
        }

        String targetClientId = getGoogleClientId();
        GoogleIdToken idToken = null;

        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance()
            )
            .setAudience(Collections.singletonList(targetClientId))
            .build();

            idToken = verifier.verify(idTokenString.trim());
        } catch (Exception e) {
            log.warn("GoogleIdTokenVerifier direct verification threw exception: {}", e.getMessage());
        }

        if (idToken != null) {
            GoogleIdToken.Payload payload = idToken.getPayload();
            if (payload == null) {
                throw new UnauthorizedException("Mã xác thực Google không hợp lệ.");
            }
            if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
                throw new BusinessException("Tài khoản Google của bạn chưa được xác minh email.");
            }
            String sub = payload.getSubject();
            String email = payload.getEmail();
            String name = (String) payload.get("name");
            String picture = (String) payload.get("picture");
            return new GoogleUserInfo(sub, email, name, picture);
        }

        // Secondary verification via Google's tokeninfo endpoint (validates cryptographically against Google servers)
        try {
            RestTemplate restTemplate = new RestTemplate();
            String tokenInfoUrl = "https://oauth2.googleapis.com/tokeninfo?id_token=" + idTokenString.trim();
            @SuppressWarnings("unchecked")
            Map<String, Object> tokenInfo = restTemplate.getForObject(tokenInfoUrl, Map.class);
            if (tokenInfo != null) {
                String aud = (String) tokenInfo.get("aud");
                if (aud == null || !aud.equals(targetClientId)) {
                    log.error("Google token audience mismatch. Expected: {}, Got: {}", targetClientId, aud);
                    throw new UnauthorizedException("Mã xác thực Google không thuộc về ứng dụng này.");
                }

                String iss = (String) tokenInfo.get("iss");
                if (iss == null || (!iss.equals("accounts.google.com") && !iss.equals("https://accounts.google.com"))) {
                    log.error("Google token issuer invalid: {}", iss);
                    throw new UnauthorizedException("Nhà phát hành token Google không hợp lệ.");
                }

                Object expObj = tokenInfo.get("exp");
                if (expObj != null) {
                    long expSec = Long.parseLong(expObj.toString());
                    if (System.currentTimeMillis() / 1000 > expSec) {
                        throw new UnauthorizedException("Mã xác thực Google đã hết hạn.");
                    }
                }

                Object emailVerifiedObj = tokenInfo.get("email_verified");
                boolean emailVerified = emailVerifiedObj != null && ("true".equalsIgnoreCase(emailVerifiedObj.toString()) || Boolean.TRUE.equals(emailVerifiedObj));
                if (!emailVerified) {
                    throw new BusinessException("Tài khoản Google của bạn chưa được xác minh email.");
                }

                String sub = (String) tokenInfo.get("sub");
                String email = (String) tokenInfo.get("email");
                String name = (String) tokenInfo.get("name");
                String picture = (String) tokenInfo.get("picture");

                if (sub == null || email == null) {
                    throw new UnauthorizedException("Không thể trích xuất định danh từ token Google.");
                }

                return new GoogleUserInfo(sub, email, name, picture);
            }
        } catch (UnauthorizedException | BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google tokeninfo verification failed: {}", e.getMessage());
            throw new UnauthorizedException("Mã xác thực Google không hợp lệ hoặc đã hết hạn: " + e.getMessage());
        }

        throw new UnauthorizedException("Xác thực token Google thất bại.");
    }

    @Transactional
    public AuthResponse loginWithGoogle(GoogleLoginRequest request) {
        if (request == null || request.getIdToken() == null || request.getIdToken().trim().isEmpty()) {
            throw new BusinessException("Mã xác thực Google ID Token là bắt buộc.");
        }

        GoogleUserInfo googleUser = verifyGoogleIdToken(request.getIdToken());
        String googleSub = googleUser.sub();
        String normalizedEmail = googleUser.email().trim().toLowerCase();
        String fullName = googleUser.name();
        String avatarUrl = googleUser.pictureUrl();

        // 1. Search for existing user by Google Subject ID (stable identifier)
        User user = userRepository.findByGoogleId(googleSub).orElse(null);

        if (user == null) {
            // 2. Check if an account already exists with this email
            Optional<User> existingUserByEmail = userRepository.findByEmail(normalizedEmail);
            if (existingUserByEmail.isPresent()) {
                User emailUser = existingUserByEmail.get();
                // Safe linking policy: If account was registered via email/password, reject automatic takeover
                if (emailUser.getGoogleId() == null || !emailUser.getGoogleId().equals(googleSub)) {
                    throw new BusinessException("Email này (" + normalizedEmail + ") đã được đăng ký bằng mật khẩu. Vui lòng đăng nhập bằng mật khẩu của bạn.");
                }
                user = emailUser;
            } else {
                // 3. User does not exist: create a new ArtNest user
                if (fullName == null || fullName.trim().isEmpty()) {
                    fullName = normalizedEmail.split("@")[0];
                }

                user = User.builder()
                        .email(normalizedEmail)
                        .fullName(fullName.trim())
                        .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                        .googleId(googleSub)
                        .avatarUrl(avatarUrl)
                        .role(Role.USER) // Never auto-grant ADMIN
                        .isActive(true)
                        .build();
                user = userRepository.save(user);

                // Create initial learner profile
                UserProfile profile = UserProfile.builder()
                        .user(user)
                        .profileType(ProfileType.LEARNER)
                        .displayName(user.getFullName())
                        .avatarUrl(user.getAvatarUrl())
                        .ageGroup(AgeGroup.ADULT)
                        .isApproved(false)
                        .build();
                userProfileRepository.save(profile);
                log.info("Created new ArtNest user via Google Sign-In with subject: {}", googleSub);
            }
        }

        // 4. Verify account status
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new DisabledException("Tài khoản của bạn đã bị khóa.");
        }

        // Update avatar if currently empty and Google provided one
        if ((user.getAvatarUrl() == null || user.getAvatarUrl().isEmpty()) && avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
            userRepository.save(user);
        }

        // 5. Build and return ArtNest AuthResponse with JWT tokens
        return buildAuthResponse(user);
    }

    public String getGoogleClientId() {
        return googleClientId != null && !googleClientId.isBlank()
                ? googleClientId.trim()
                : "4555221892-omq3ncgr2ib4frohuct3mhsv5rs02m1n.apps.googleusercontent.com";
    }

    // ==========================================
    // ADDRESS MANAGEMENT OPERATIONS
    // ==========================================

    public List<AddressResponse> getUserAddresses(String email) {
        User user = findUserByEmail(email);
        return addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(user.getId())
                .stream()
                .map(this::mapToAddressResponse)
                .toList();
    }

    @Transactional
    public AddressResponse createAddress(String email, AddressRequest request) {
        User user = findUserByEmail(email);

        boolean isFirstAddress = addressRepository.countByUserId(user.getId()) == 0;

        // If first address or requested as default, handle default flag
        boolean shouldBeDefault = isFirstAddress || Boolean.TRUE.equals(request.getIsDefault());

        if (shouldBeDefault) {
            addressRepository.clearDefaultByUserId(user.getId());
        }

        UserAddress address = UserAddress.builder()
                .user(user)
                .receiverName(request.getReceiverName().trim())
                .phone(request.getPhone().trim())
                .province(request.getProvince().trim())
                .district(request.getDistrict().trim())
                .ward(request.getWard().trim())
                .streetAddress(request.getStreetAddress().trim())
                .isDefault(shouldBeDefault)
                .build();

        address = addressRepository.save(address);
        return mapToAddressResponse(address);
    }

    @Transactional
    public AddressResponse updateAddress(String email, Integer addressId, AddressRequest request) {
        User user = findUserByEmail(email);
        UserAddress address = findAddressByIdAndUser(addressId, user.getId());

        address.setReceiverName(request.getReceiverName().trim());
        address.setPhone(request.getPhone().trim());
        address.setProvince(request.getProvince().trim());
        address.setDistrict(request.getDistrict().trim());
        address.setWard(request.getWard().trim());
        address.setStreetAddress(request.getStreetAddress().trim());

        if (Boolean.TRUE.equals(request.getIsDefault()) && !address.getIsDefault()) {
            addressRepository.clearDefaultByUserId(user.getId());
            address.setIsDefault(true);
        }

        address = addressRepository.save(address);
        return mapToAddressResponse(address);
    }

    @Transactional
    public void deleteAddress(String email, Integer addressId) {
        User user = findUserByEmail(email);
        UserAddress address = findAddressByIdAndUser(addressId, user.getId());
        addressRepository.delete(address);
    }

    @Transactional
    public AddressResponse setDefaultAddress(String email, Integer addressId) {
        User user = findUserByEmail(email);
        UserAddress address = findAddressByIdAndUser(addressId, user.getId());

        addressRepository.clearDefaultByUserId(user.getId());
        address.setIsDefault(true);
        address = addressRepository.save(address);

        return mapToAddressResponse(address);
    }

    // ==========================================
    // ADMIN USER MANAGEMENT OPERATIONS
    // ==========================================

    public Page<UserResponse> getAllUsers(String search, Role role, Boolean isActive, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return userRepository.findAllWithFilters(search, role, isActive, pageable)
                .map(this::mapToUserResponse);
    }

    public UserResponse getUserById(Integer id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        return mapToUserResponse(user);
    }

    @Transactional
    public UserResponse updateUserStatus(Integer id, Boolean isActive) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        // Prevent deactivating the last active admin
        if (!isActive && user.getRole() == Role.ADMIN) {
            long activeAdminCount = userRepository.countByRoleAndIsActive(Role.ADMIN, true);
            if (activeAdminCount <= 1) {
                throw new BusinessException("Cannot deactivate the last active admin account");
            }
        }

        user.setIsActive(isActive);
        user = userRepository.save(user);

        return mapToUserResponse(user);
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    private UserAddress findAddressByIdAndUser(Integer addressId, Integer userId) {
        return addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address", "id", addressId));
    }

    private AuthResponse buildAuthResponse(User user) {
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .build();

        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshTokenValue = jwtService.generateRefreshToken(userDetails);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessExpirationMs() / 1000)
                .refreshToken(refreshTokenValue)
                .user(mapToUserResponse(user))
                .build();
    }

    private UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole())
                .avatarUrl(user.getAvatarUrl())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    private AddressResponse mapToAddressResponse(UserAddress address) {
        return AddressResponse.builder()
                .id(address.getId())
                .receiverName(address.getReceiverName())
                .phone(address.getPhone())
                .province(address.getProvince())
                .district(address.getDistrict())
                .ward(address.getWard())
                .streetAddress(address.getStreetAddress())
                .isDefault(address.getIsDefault())
                .createdAt(address.getCreatedAt())
                .build();
    }
}
