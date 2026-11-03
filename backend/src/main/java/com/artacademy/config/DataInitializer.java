package com.artacademy.config;

import com.artacademy.entity.User;
import com.artacademy.entity.UserAddress;
import com.artacademy.entity.UserProfile;
import com.artacademy.entity.UserRoleHistory;
import com.artacademy.enums.AgeGroup;
import com.artacademy.enums.ProfileType;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserAddressRepository;
import com.artacademy.repository.UserProfileRepository;
import com.artacademy.repository.UserRepository;
import com.artacademy.repository.UserRoleHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!prod")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final String DEFAULT_TEST_PASSWORD = "ArtNest@123";

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserAddressRepository userAddressRepository;
    private final UserRoleHistoryRepository userRoleHistoryRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.enabled:true}")
    private boolean seedEnabled;

    public DataInitializer(UserRepository userRepository,
                           UserProfileRepository userProfileRepository,
                           UserAddressRepository userAddressRepository,
                           UserRoleHistoryRepository userRoleHistoryRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.userAddressRepository = userAddressRepository;
        this.userRoleHistoryRepository = userRoleHistoryRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Data initialization is disabled via app.seed.enabled=false");
            return;
        }

        log.info("============================================================");
        log.info("ArtNest: Starting RBAC & Test Data Initialization...");
        log.info("============================================================");

        // 1. ADMIN TEST ACCOUNT
        User admin = seedUser(
                "Test Admin",
                "admin.test@artnest.com",
                Role.ADMIN,
                "0909999999",
                "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=400&q=80"
        );
        seedProfile(admin, ProfileType.LEARNER, admin.getFullName(), "Quản trị viên toàn hệ thống ArtNest Platform.", true);
        seedAddress(admin, "Test Admin", "0909999999", "Hà Nội", "Ba Đình", "Kim Mã", "101 Đường Kim Mã, Phường Kim Mã");

        // 2. INSTRUCTOR TEST ACCOUNT
        User instructor = seedUser(
                "Test Instructor",
                "instructor.test@artnest.com",
                Role.INSTRUCTOR,
                "0987654321",
                "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=400&q=80"
        );
        seedProfile(
                instructor,
                ProfileType.INSTRUCTOR,
                instructor.getFullName(),
                "Họa sĩ & Giảng viên mỹ thuật đương đại với hơn 8 năm kinh nghiệm giảng dạy ký họa, sơn dầu và màu nước.",
                true
        );
        seedAddress(instructor, "Test Instructor", "0987654321", "Hồ Chí Minh", "Quận 1", "Bến Nghé", "456 Đường Lê Lợi, Phường Bến Nghé");

        // 3. STAFF TEST ACCOUNT
        User staff = seedUser(
                "Test Staff",
                "staff.test@artnest.com",
                Role.STAFF,
                "0905123456",
                "https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=400&q=80"
        );
        seedProfile(staff, ProfileType.LEARNER, staff.getFullName(), "Chuyên viên vận hành đơn hàng, kho họa cụ & hỗ trợ học viên.", true);
        seedAddress(staff, "Test Staff", "0905123456", "Đà Nẵng", "Hải Châu", "Hải Châu I", "789 Đường Nguyễn Văn Linh, Phường Hải Châu I");

        // 4. USER (LEARNER) TEST ACCOUNT
        User user = seedUser(
                "Test User",
                "user.test@artnest.com",
                Role.USER,
                "0912345678",
                "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=400&q=80"
        );
        seedProfile(user, ProfileType.LEARNER, user.getFullName(), "Người yêu hội họa, đam mê vẽ ký họa đường phố và màu nước.", false);
        seedAddress(user, "Test User", "0912345678", "Hà Nội", "Cầu Giấy", "Dịch Vọng", "123 Đường Cầu Giấy, Phường Dịch Vọng");

        // Seed initial audit role history records for demo/verification
        seedRoleHistory(instructor, admin, Role.USER, Role.INSTRUCTOR, "Xét duyệt hồ sơ giảng viên chuyên ngành Mỹ thuật & cấp quyền INSTRUCTOR");
        seedRoleHistory(staff, admin, Role.USER, Role.STAFF, "Tiếp nhận nhân sự điều phối vận hành kho và gán quyền STAFF");

        log.info("============================================================");
        log.info("ArtNest: Test accounts ready (Password: ArtNest@123):");
        log.info(" - USER:       user.test@artnest.com");
        log.info(" - INSTRUCTOR: instructor.test@artnest.com");
        log.info(" - STAFF:      staff.test@artnest.com");
        log.info(" - ADMIN:      admin.test@artnest.com");
        log.info("============================================================");
    }

    private User seedUser(String fullName, String email, Role role, String phone, String avatarUrl) {
        String normalizedEmail = email.trim().toLowerCase();
        return userRepository.findByEmail(normalizedEmail).orElseGet(() -> {
            User newUser = User.builder()
                    .fullName(fullName.trim())
                    .email(normalizedEmail)
                    .passwordHash(passwordEncoder.encode(DEFAULT_TEST_PASSWORD))
                    .role(role)
                    .phone(phone)
                    .avatarUrl(avatarUrl)
                    .isActive(true)
                    .tokenVersion(1)
                    .build();
            User saved = userRepository.save(newUser);
            log.info("Seeded test account [{}]: {}", role, normalizedEmail);
            return saved;
        });
    }

    private void seedProfile(User user, ProfileType profileType, String displayName, String bio, boolean approved) {
        if (!userProfileRepository.findByUserId(user.getId()).isEmpty()) {
            return;
        }
        UserProfile profile = UserProfile.builder()
                .user(user)
                .profileType(profileType)
                .displayName(displayName)
                .avatarUrl(user.getAvatarUrl())
                .ageGroup(AgeGroup.ADULT)
                .bio(bio)
                .isApproved(approved)
                .build();
        userProfileRepository.save(profile);
    }

    private void seedAddress(User user, String receiverName, String phone, String province, String district, String ward, String streetAddress) {
        if (userAddressRepository.countByUserId(user.getId()) > 0) {
            return;
        }
        UserAddress address = UserAddress.builder()
                .user(user)
                .receiverName(receiverName)
                .phone(phone)
                .province(province)
                .district(district)
                .ward(ward)
                .streetAddress(streetAddress)
                .isDefault(true)
                .build();
        userAddressRepository.save(address);
    }

    private void seedRoleHistory(User targetUser, User adminUser, Role oldRole, Role newRole, String reason) {
        if (userRoleHistoryRepository.findByUserIdOrderByChangedAtDesc(targetUser.getId()).isEmpty()) {
            UserRoleHistory history = UserRoleHistory.builder()
                    .user(targetUser)
                    .changedBy(adminUser)
                    .oldRole(oldRole)
                    .newRole(newRole)
                    .reason(reason)
                    .build();
            userRoleHistoryRepository.save(history);
        }
    }
}
