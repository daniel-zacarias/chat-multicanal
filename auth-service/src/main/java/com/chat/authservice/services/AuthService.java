package com.chat.authservice.services;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.chat.authservice.dtos.AuthResponse;
import com.chat.authservice.dtos.LoginRequest;
import com.chat.authservice.dtos.RefreshRequest;
import com.chat.authservice.dtos.RegisterRequest;
import com.chat.authservice.dtos.UserResponse;
import com.chat.authservice.models.User;
import com.chat.authservice.repositories.UserRepository;
import com.chat.authservice.security.JwtService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    // Computed at startup to equalize login timing when username is not found
    private String dummyHash;

    @PostConstruct
    void init() {
        dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration could not be completed");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration could not be completed");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));

        User saved = userRepository.save(user);
        log.info("User registered: id={}", saved.getId());

        String refreshToken = refreshTokenService.createRefreshToken(saved);
        return new AuthResponse(jwtService.generateToken(saved), refreshToken, toResponse(saved));
    }

    public AuthResponse login(LoginRequest request) {
        Optional<User> userOpt = userRepository.findByUsername(request.username());

        if (userOpt.isEmpty()) {
            // Run BCrypt to equalize timing and prevent username enumeration via side-channel
            passwordEncoder.matches(request.password(), dummyHash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("Failed login attempt for username={}", request.username());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        log.info("Successful login for user id={}", user.getId());
        String refreshToken = refreshTokenService.createRefreshToken(user);
        return new AuthResponse(jwtService.generateToken(user), refreshToken, toResponse(user));
    }

    public AuthResponse refresh(RefreshRequest request) {
        User user = refreshTokenService.validateAndRotate(request.refreshToken());
        String newRefreshToken = refreshTokenService.createRefreshToken(user);
        log.info("Token refreshed for user id={}", user.getId());
        return new AuthResponse(jwtService.generateToken(user), newRefreshToken, toResponse(user));
    }

    public void logout(RefreshRequest request) {
        refreshTokenService.revokeToken(request.refreshToken());
    }

    public UserResponse me(User user) {
        return toResponse(user);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId().toString(), user.getUsername(), user.getEmail(), user.getCreatedAt());
    }
}
