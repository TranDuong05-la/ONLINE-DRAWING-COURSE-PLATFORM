package com.artacademy.common;

import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Tạo sẵn 1 tài khoản ADMIN và 1 tài khoản học viên để thử app (chỉ khi app.seed.users.enabled=true,
 * mặc định bật ở profile "h2", KHÔNG bật khi chạy MySQL thật).
 * Email/mật khẩu đọc từ cấu hình app.seed.* (xem application-h2.yaml). Bỏ qua nếu email đã tồn tại.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "app.seed.users.enabled", havingValue = "true")
public class UserDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserDataSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final String adminEmail;
    private final String adminPassword;
    private final String learnerEmail;
    private final String learnerPassword;

    public UserDataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          @Value("${app.seed.admin.email}") String adminEmail,
                          @Value("${app.seed.admin.password}") String adminPassword,
                          @Value("${app.seed.learner.email}") String learnerEmail,
                          @Value("${app.seed.learner.password}") String learnerPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.learnerEmail = learnerEmail;
        this.learnerPassword = learnerPassword;
    }

    @Override
    public void run(String... args) {
        createIfMissing("Quản trị viên", adminEmail, adminPassword, Role.ADMIN);
        createIfMissing("Học viên mẫu", learnerEmail, learnerPassword, Role.USER);
    }

    private void createIfMissing(String fullName, String email, String rawPassword, Role role) {
        String normalized = email.trim().toLowerCase();
        if (userRepository.existsByEmail(normalized)) {
            return;
        }
        userRepository.save(User.builder()
                .fullName(fullName)
                .email(normalized)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .isActive(true)
                .build());
        log.info("Đã tạo tài khoản mẫu {} ({})", normalized, role);
    }
}
