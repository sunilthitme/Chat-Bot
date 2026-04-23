package com.example.internalchatbot.service;

import com.example.internalchatbot.dto.GrantAccessRequest;
import com.example.internalchatbot.dto.LoginRequest;
import com.example.internalchatbot.dto.LoginResponse;
import com.example.internalchatbot.dto.UserAccessResponse;
import com.example.internalchatbot.entity.AppUser;
import com.example.internalchatbot.entity.UserRole;
import com.example.internalchatbot.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Service handles login, token validation, and admin user access.
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String ALLOWED_DOMAIN = "@td.com";

    private final AppUserRepository appUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Map<String, AppUser> sessions = new ConcurrentHashMap<>();

    public AuthService(AppUserRepository appUserRepository, BCryptPasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Login started. email={}", email);
        validateTdEmail(email);

        AppUser appUser = appUserRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    log.warn("Login failed because user is not registered. email={}", email);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
                });

        if (!appUser.isActive() || !passwordEncoder.matches(request.getPassword(), appUser.getPasswordHash())) {
            log.warn("Login failed because credentials are invalid or inactive. email={}", email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        String token = UUID.randomUUID().toString();
        sessions.put(token, appUser);
        log.info("Login completed. email={}, role={}", email, appUser.getRole());
        return new LoginResponse(token, appUser.getEmail(), appUser.getRole());
    }

    public UserAccessResponse grantUserAccess(String token, GrantAccessRequest request) {
        AppUser admin = requireRole(token, UserRole.ADMIN);
        String email = normalizeEmail(request.getEmail());
        log.info("Grant access started. adminEmail={}, userEmail={}", admin.getEmail(), email);
        validateTdEmail(email);

        AppUser appUser = appUserRepository.findByEmailIgnoreCase(email).orElseGet(AppUser::new);
        appUser.setEmail(email);
        appUser.setPasswordHash(passwordEncoder.encode(request.getTemporaryPassword()));
        appUser.setRole(UserRole.USER);
        appUser.setActive(true);

        AppUser savedUser = appUserRepository.save(appUser);
        log.info("Grant access completed. adminEmail={}, userEmail={}", admin.getEmail(), savedUser.getEmail());
        return new UserAccessResponse(savedUser.getEmail(), savedUser.getRole(), savedUser.isActive());
    }

    public AppUser requireRole(String token, UserRole role) {
        AppUser appUser = requireAuthenticated(token);
        if (appUser.getRole() != role) {
            log.warn("Role check failed. email={}, requiredRole={}, actualRole={}", appUser.getEmail(), role, appUser.getRole());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return appUser;
    }

    public AppUser requireAuthenticated(String token) {
        if (token == null || token.isBlank() || !sessions.containsKey(token)) {
            log.warn("Token validation failed");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please login again");
        }

        AppUser appUser = sessions.get(token);
        if (!appUser.isActive()) {
            log.warn("Inactive user token rejected. email={}", appUser.getEmail());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please login again");
        }
        return appUser;
    }

    private void validateTdEmail(String email) {
        if (!email.endsWith(ALLOWED_DOMAIN)) {
            log.warn("Email domain rejected. email={}", email);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only td.com users can login");
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
