package com.artacademy.service;

import com.artacademy.entity.User;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;


@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        // Use ROLE_ prefix for Spring Security compatibility with hasRole()
        return new AuthenticatedUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getRole(), Boolean.TRUE.equals(user.getIsActive()));

    }
}
