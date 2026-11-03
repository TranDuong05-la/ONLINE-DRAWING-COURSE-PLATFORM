package com.artacademy.service;

import com.artacademy.dto.*;
import com.artacademy.entity.User;
import com.artacademy.entity.UserAddress;
import com.artacademy.entity.UserRoleHistory;
import com.artacademy.enums.Role;
import com.artacademy.exception.BusinessException;
import com.artacademy.exception.DuplicateResourceException;
import com.artacademy.exception.ResourceNotFoundException;
import com.artacademy.exception.UnauthorizedException;
import com.artacademy.repository.UserAddressRepository;
import com.artacademy.repository.UserRepository;
import com.artacademy.repository.UserRoleHistoryRepository;
import com.artacademy.security.AuthenticatedUserPrincipal;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;

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
    private final UserRoleHistoryRepository userRoleHistoryRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final GoogleTokenInfoClient googleTokenInfoClient;
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
                       UserRoleHistoryRepository userRoleHistoryRepository,
                       EmailService emailService,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthenticationManager authenticationManager,
                       GoogleTokenInfoClient googleTokenInfoClient) {
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.userProfileRepository = userProfileRepository;
        this.userRoleHistoryRepository = userRoleHistoryRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.googleTokenInfoClient = googleTokenInfoClient;
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

        if (user.getTokenVersion() == null || user.getTokenVersion() < 1
                || !jwtService.isRefreshTokenValid(token, user.getTokenVersion())) {
            throw new UnauthorizedException("Refresh token has been revoked or expired");
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

    record GoogleUserInfo(String sub, String email, String name, String pictureUrl) {}

    GoogleUserInfo verifyGoogleIdToken(String idTokenString) {
        if (idTokenString == null || idTokenString.trim().isEmpty()) {
            throw new BusinessException("Mã xác thực Google ID Token không được để trống.");
        }

        String targetClientId = getGoogleClientId();
        if (targetClientId.isBlank()) {
            log.error("Google Client ID is not configured on the backend!");
            throw new BusinessException("Hệ thống chưa cấu hình Google Client ID.");
        }
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
            Map<String, Object> tokenInfo = googleTokenInfoClient.fetch(idTokenString.trim());

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
            // Do not log or return e.getMessage(): RestTemplate errors embed the request URL,
            // which contains the Google ID token.
            log.error("Google tokeninfo verification failed: {}", e.getClass().getSimpleName());
            throw new UnauthorizedException("Mã xác thực Google không hợp lệ hoặc đã hết hạn.");
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
                // Link Google account to existing email user if not yet linked
                if (emailUser.getGoogleId() == null) {
                    log.info("Linking Google account ({}) to existing email user: {}", googleSub, normalizedEmail);
                    emailUser.setGoogleId(googleSub);
                    if (emailUser.getAvatarUrl() == null && avatarUrl != null) {
                        emailUser.setAvatarUrl(avatarUrl);
                    }
                    user = userRepository.save(emailUser);
                } else if (emailUser.getGoogleId().equals(googleSub)) {
                    user = emailUser;
                } else {
                    throw new BusinessException("Email này (" + normalizedEmail + ") đã được liên kết với một tài khoản Google khác.");
                }
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
                : "";
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
        return getAllUsers(search, role, isActive, page, size, "createdAt", "desc");
    }

    public Page<UserResponse> getAllUsers(String search, Role role, Boolean isActive, int page, int size,
                                          String sortBy, String sortDirection) {
        Set<String> allowedSortFields = Set.of("id", "createdAt", "updatedAt", "fullName", "email", "role", "isActive");
        String resolvedSort = sortBy == null || sortBy.isBlank() ? "createdAt" : sortBy;
        String resolvedDirection = sortDirection == null || sortDirection.isBlank() ? "desc" : sortDirection;
        if (!allowedSortFields.contains(resolvedSort)) {
            throw new BusinessException("Unsupported user sort field");
        }
        if (!resolvedDirection.equalsIgnoreCase("asc") && !resolvedDirection.equalsIgnoreCase("desc")) {
            throw new BusinessException("Unsupported user sort direction");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException("Invalid user page or page size");
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(resolvedDirection), resolvedSort));
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
        if (id == null || isActive == null) throw new BusinessException("User id and isActive are required");
        List<User> activeAdmins = userRepository.findActiveByRoleForUpdate(Role.ADMIN);
        User user = activeAdmins.stream().filter(candidate -> candidate.getId().equals(id)).findFirst()
                .orElseGet(() -> userRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new ResourceNotFoundException("User", "id", id)));
        if (Boolean.TRUE.equals(user.getIsActive()) == isActive) return mapToUserResponse(user);

        // Prevent deactivating the last active admin
        if (!isActive && user.getRole() == Role.ADMIN && Boolean.TRUE.equals(user.getIsActive())) {
            if (activeAdmins.size() <= 1) {
                throw new BusinessException("Cannot deactivate the last active admin account");
            }
        }

        user.setIsActive(isActive);
        incrementTokenVersion(user);
        user = userRepository.save(user);

        return mapToUserResponse(user);
    }

    @Transactional
    public UserResponse updateUserRole(Integer actorUserId, Integer targetUserId, UpdateUserRoleRequest request) {
        if (actorUserId == null || targetUserId == null || request == null || request.role() == null) {
            throw new BusinessException("A valid actor, target, and role are required");
        }

        // Lock the complete active-admin set in stable ID order before the target row. Both role and
        // status changes use this order, making the last-active-admin check a serialized current read.
        List<User> activeAdmins = userRepository.findActiveByRoleForUpdate(Role.ADMIN);
        User actor = activeAdmins.stream().filter(candidate -> candidate.getId().equals(actorUserId)).findFirst()
                .orElseGet(() -> userRepository.findByIdForUpdate(actorUserId)
                        .orElseThrow(() -> new UnauthorizedException("Administrator identity is invalid")));
        if (actor.getRole() != Role.ADMIN || !Boolean.TRUE.equals(actor.getIsActive())) {
            throw new UnauthorizedException("Only an active administrator can update user roles");
        }
        User target = activeAdmins.stream().filter(candidate -> candidate.getId().equals(targetUserId)).findFirst()
                .orElseGet(() -> userRepository.findByIdForUpdate(targetUserId)
                        .orElseThrow(() -> new ResourceNotFoundException("User", "id", targetUserId)));

        Role previousRole = target.getRole();
        Role nextRole = request.role();
        if (previousRole == nextRole) return mapToUserResponse(target);
        if (previousRole == Role.ADMIN && Boolean.TRUE.equals(target.getIsActive()) && nextRole != Role.ADMIN
                && activeAdmins.size() <= 1) {
            throw new BusinessException("Cannot change the role of the last active administrator");
        }

        target.setRole(nextRole);
        incrementTokenVersion(target);
        target = userRepository.save(target);
        String reason = request.reason() == null || request.reason().isBlank()
                ? null : request.reason().trim();
        userRoleHistoryRepository.save(UserRoleHistory.create(target, actor, previousRole, nextRole, reason));
        return mapToUserResponse(target);
    }

    @Transactional(readOnly = true)
    public List<RoleHistoryResponse> getUserRoleHistory(Integer userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", "id", userId);
        }
        return userRoleHistoryRepository.findHistoryForUser(userId).stream()
                .map(history -> new RoleHistoryResponse(history.getId(), history.getUser().getId(),
                        history.getUser().getEmail(), history.getUser().getFullName(),
                        history.getChangedBy().getId(), history.getChangedBy().getEmail(),
                        history.getChangedBy().getFullName(), history.getOldRole(), history.getNewRole(),
                        history.getReason(), history.getChangedAt()))
                .toList();
    }

    private static void incrementTokenVersion(User user) {
        int version = user.getTokenVersion() == null ? 1 : user.getTokenVersion();
        if (version < 1 || version == Integer.MAX_VALUE) {
            throw new BusinessException("User token version cannot be incremented");
        }
        user.setTokenVersion(version + 1);
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
        UserDetails userDetails = new AuthenticatedUserPrincipal(user.getId(), user.getEmail(),
                user.getPasswordHash() != null ? user.getPasswordHash() : "", user.getRole(),
                Boolean.TRUE.equals(user.getIsActive()), user.getTokenVersion());

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
