package com.artacademy.supply.common;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.artacademy.entity.User;
import com.artacademy.exception.UnauthorizedException;
import com.artacademy.repository.UserRepository;

/** Đọc người dùng hiện tại từ SecurityContext (principal của A là email). */
@Component
public class SupplyCurrentUser {

    private final UserRepository userRepository;

    public SupplyCurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public SupplyUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            throw new UnauthorizedException("Vui lòng đăng nhập");
        }
        User user = userRepository.findByEmail(auth.getName().trim().toLowerCase())
                .orElseThrow(() -> new UnauthorizedException("Vui lòng đăng nhập"));
        return new SupplyUser(user.getId(), user.getRole());
    }
}
