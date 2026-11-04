package com.artacademy.controller;

import com.artacademy.dto.*;
import com.artacademy.enums.Role;
import com.artacademy.service.AuthService;
import com.artacademy.service.CloudinaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Authentication & User Management", description = "Registration, login, user profile, address management, and admin user administration")
public class AuthController {

    private final AuthService authService;
    private final CloudinaryService cloudinaryService;

    public AuthController(AuthService authService, CloudinaryService cloudinaryService) {
        this.authService = authService;
        this.cloudinaryService = cloudinaryService;
    }

    // ==========================================
    // AUTHENTICATION ENDPOINTS
    // ==========================================

    @PostMapping("/api/auth/register")
    @Operation(summary = "Register a new user account", description = "Creates a new USER account. Role is always set to USER regardless of request payload.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful", response));
    }

    @PostMapping("/api/auth/login")
    @Operation(summary = "Login with email and password", description = "Authenticates the user and returns access and refresh tokens.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/api/auth/refresh")
    @Operation(summary = "Refresh access token", description = "Validates the refresh token and returns a new access token. Old refresh token is rotated.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refresh(request);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", response));
    }

    @PostMapping("/api/auth/logout")
    @Operation(summary = "Logout and revoke refresh token", description = "Revokes the provided refresh token.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<Void>> logout(@RequestBody RefreshTokenRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully"));
    }

    @PostMapping("/api/auth/forgot-password")
    @Operation(summary = "Send password reset OTP via email", description = "Sends a 6-digit OTP code to the user's email for password recovery.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.sendPasswordResetOtp(request);
        return ResponseEntity.ok(ApiResponse.success("Mã xác thực đã được gửi về email của bạn (nếu email tồn tại trên hệ thống)."));
    }

    @PostMapping("/api/auth/reset-password")
    @Operation(summary = "Reset password with OTP", description = "Validates the 6-digit OTP code and updates the user's password.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPasswordWithOtp(request);
        return ResponseEntity.ok(ApiResponse.success("Mật khẩu đã được đặt lại thành công. Bạn có thể đăng nhập ngay."));
    }

    @PostMapping("/api/auth/google")
    @Operation(summary = "Login with Google", description = "Authenticates or registers user via Google account information and returns JWT tokens.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<AuthResponse>> loginWithGoogle(@RequestBody GoogleLoginRequest request) {
        AuthResponse response = authService.loginWithGoogle(request);
        return ResponseEntity.ok(ApiResponse.success("Đăng nhập Google thành công", response));
    }

    @GetMapping("/api/auth/google/oauth-config")
    @Operation(summary = "Get Google OAuth 2.0 configuration", description = "Returns Google Client ID and OAuth parameters.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<Map<String, String>>> getGoogleOAuthConfig() {
        String clientId = authService.getGoogleClientId();
        return ResponseEntity.ok(ApiResponse.success("Google OAuth config retrieved", Map.of(
                "clientId", clientId,
                "scope", "openid email profile"
        )));
    }

    @GetMapping("/api/auth/me")
    @Operation(summary = "Get current authenticated user info", description = "Returns the profile of the currently authenticated user from SecurityContext.", tags = {"Authentication"})
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        UserResponse response = authService.getCurrentUser(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("User retrieved successfully", response));
    }

    // ==========================================
    // USER PROFILE ENDPOINTS
    // ==========================================

    @GetMapping("/api/users/me")
    @Operation(summary = "Get current user profile", description = "Returns the profile of the currently authenticated user.", tags = {"User Profile"})
    public ResponseEntity<ApiResponse<UserResponse>> getProfile(@AuthenticationPrincipal UserDetails userDetails) {
        UserResponse response = authService.getCurrentUser(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Profile retrieved successfully", response));
    }

    @PutMapping("/api/users/me")
    @Operation(summary = "Update current user profile", description = "Updates allowed fields: fullName, phone, avatarUrl. Cannot change email, role, or password through this endpoint.", tags = {"User Profile"})
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request) {
        UserResponse response = authService.updateProfile(userDetails.getUsername(), request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", response));
    }

    @PostMapping(value = "/api/users/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload user avatar", description = "Uploads a real image file to Cloudinary and updates user profile avatar.", tags = {"User Profile"})
    public ResponseEntity<ApiResponse<UserResponse>> uploadAvatar(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file) {
        UserResponse response = authService.updateAvatar(userDetails.getUsername(), file);
        return ResponseEntity.ok(ApiResponse.success("Avatar uploaded successfully", response));
    }

    @PostMapping(value = "/api/upload/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload single image", description = "Uploads an image file to Cloudinary and returns its secure URL.", tags = {"Upload"})
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadImage(
            @RequestParam("file") MultipartFile file) {
        String url = cloudinaryService.uploadImage(file);
        return ResponseEntity.ok(ApiResponse.success("Image uploaded successfully", Map.of("url", url)));
    }

    @PutMapping("/api/users/me/password")
    @Operation(summary = "Change password", description = "Changes the user's password. Requires current password verification. Revokes all existing refresh tokens.", tags = {"User Profile"})
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(userDetails.getUsername(), request);
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully"));
    }

    // ==========================================
    // ADDRESS MANAGEMENT ENDPOINTS
    // ==========================================

    @GetMapping("/api/users/me/addresses")
    @Operation(summary = "List all addresses", description = "Returns all addresses for the currently authenticated user, ordered by default status and creation date.", tags = {"Address Management"})
    public ResponseEntity<ApiResponse<List<AddressResponse>>> getAddresses(
            @AuthenticationPrincipal UserDetails userDetails) {
        List<AddressResponse> addresses = authService.getUserAddresses(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Addresses retrieved successfully", addresses));
    }

    @PostMapping("/api/users/me/addresses")
    @Operation(summary = "Create a new address", description = "Creates a new shipping address. The first address is automatically set as default.", tags = {"Address Management"})
    public ResponseEntity<ApiResponse<AddressResponse>> createAddress(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody AddressRequest request) {
        AddressResponse response = authService.createAddress(userDetails.getUsername(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Address created successfully", response));
    }

    @PutMapping("/api/users/me/addresses/{id}")
    @Operation(summary = "Update an address", description = "Updates an existing address. Only the owner can update their address.", tags = {"Address Management"})
    public ResponseEntity<ApiResponse<AddressResponse>> updateAddress(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Integer id,
            @Valid @RequestBody AddressRequest request) {
        AddressResponse response = authService.updateAddress(userDetails.getUsername(), id, request);
        return ResponseEntity.ok(ApiResponse.success("Address updated successfully", response));
    }

    @DeleteMapping("/api/users/me/addresses/{id}")
    @Operation(summary = "Delete an address", description = "Deletes an address. Only the owner can delete their address.", tags = {"Address Management"})
    public ResponseEntity<ApiResponse<Void>> deleteAddress(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Integer id) {
        authService.deleteAddress(userDetails.getUsername(), id);
        return ResponseEntity.ok(ApiResponse.success("Address deleted successfully"));
    }

    @PatchMapping("/api/users/me/addresses/{id}/default")
    @Operation(summary = "Set address as default", description = "Sets the specified address as the default. Clears default flag from all other addresses.", tags = {"Address Management"})
    public ResponseEntity<ApiResponse<AddressResponse>> setDefaultAddress(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Integer id) {
        AddressResponse response = authService.setDefaultAddress(userDetails.getUsername(), id);
        return ResponseEntity.ok(ApiResponse.success("Default address updated successfully", response));
    }

    // ==========================================
    // ADMIN DASHBOARD & USER MANAGEMENT ENDPOINTS
    // ==========================================

    @GetMapping("/api/admin/dashboard")
    @Operation(summary = "Get admin dashboard overview statistics", description = "Returns aggregated platform metrics including user counts by role, courses, orders, and active accounts. Requires ADMIN role.", tags = {"Admin Dashboard"})
    public ResponseEntity<ApiResponse<AdminDashboardResponse>> getAdminDashboard() {
        AdminDashboardResponse dashboard = authService.getAdminDashboard();
        return ResponseEntity.ok(ApiResponse.success("Dashboard metrics retrieved successfully", dashboard));
    }

    @GetMapping("/api/admin/users")
    @Operation(summary = "List all users (Admin)", description = "Paginated user listing with optional search by email/name/phone, filters by role and status, and sorting. Requires ADMIN role.", tags = {"Admin User Management"})
    public ResponseEntity<ApiResponse<Page<UserResponse>>> getAllUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection) {
        Page<UserResponse> users = authService.getAllUsers(search, role, isActive, page, size, sortBy, sortDirection);
        return ResponseEntity.ok(ApiResponse.success("Users retrieved successfully", users));
    }

    @GetMapping("/api/admin/users/{id}")
    @Operation(summary = "Get user details (Admin)", description = "Returns user details by ID. Does not return password hash. Requires ADMIN role.", tags = {"Admin User Management"})
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable Integer id) {
        UserResponse user = authService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success("User retrieved successfully", user));
    }

    @PutMapping("/api/admin/users/{id}/role")
    @Operation(summary = "Change user role (Admin)", description = "Updates user role and logs the audit event to role history. Requires ADMIN role.", tags = {"Admin User Management"})
    public ResponseEntity<ApiResponse<UserResponse>> updateUserRole(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Integer id,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        UserResponse user = authService.updateUserRole(userDetails.getUsername(), id, request);
        return ResponseEntity.ok(ApiResponse.success("User role updated successfully", user));
    }

    @PutMapping("/api/admin/users/{id}/status")
    @Operation(summary = "Update user status (Admin)", description = "Updates user status (ACTIVE, INACTIVE, LOCKED). Prevents locking the last active admin. Requires ADMIN role.", tags = {"Admin User Management"})
    public ResponseEntity<ApiResponse<UserResponse>> updateUserStatusWithDto(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Integer id,
            @RequestBody UpdateUserStatusRequest request) {
        UserResponse user = authService.updateUserStatus(userDetails.getUsername(), id, request);
        return ResponseEntity.ok(ApiResponse.success(
                Boolean.TRUE.equals(user.getIsActive()) ? "User activated successfully" : "User deactivated successfully",
                user));
    }

    @PatchMapping("/api/admin/users/{id}/status")
    @Operation(summary = "Activate or deactivate a user (Admin)", description = "Toggles user active status. Cannot deactivate the last active admin. Requires ADMIN role.", tags = {"Admin User Management"})
    public ResponseEntity<ApiResponse<UserResponse>> updateUserStatus(
            @PathVariable Integer id,
            @RequestBody UpdateUserStatusRequest body) {
        // Cùng dạng dữ liệu với PUT: isActive hoặc status (ACTIVE/INACTIVE/LOCKED), có thể kèm reason
        Boolean isActive = body.resolveIsActive();
        if (isActive == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("isActive field is required"));
        }
        UserResponse user = authService.updateUserStatus(id, isActive);
        return ResponseEntity.ok(ApiResponse.success(
                isActive ? "User activated successfully" : "User deactivated successfully",
                user));
    }

    @GetMapping("/api/admin/users/{id}/role-history")
    @Operation(summary = "Get role change history (Admin)", description = "Returns chronological role modification logs for a specific user. Requires ADMIN role.", tags = {"Admin User Management"})
    public ResponseEntity<ApiResponse<List<RoleHistoryResponse>>> getUserRoleHistory(@PathVariable Integer id) {
        List<RoleHistoryResponse> history = authService.getUserRoleHistory(id);
        return ResponseEntity.ok(ApiResponse.success("Role history retrieved successfully", history));
    }
}
