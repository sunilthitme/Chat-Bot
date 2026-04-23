package com.example.internalchatbot.controller;

import com.example.internalchatbot.dto.GrantAccessRequest;
import com.example.internalchatbot.dto.LoginRequest;
import com.example.internalchatbot.dto.LoginResponse;
import com.example.internalchatbot.dto.UserAccessResponse;
import com.example.internalchatbot.service.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// REST controller exposes login and admin access APIs.
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        log.info("POST /api/auth/login started");
        LoginResponse response = authService.login(request);
        log.info("POST /api/auth/login completed");
        return response;
    }

    @PostMapping("/grant-access")
    public UserAccessResponse grantAccess(
            @RequestHeader("X-User-Token") String token,
            @Valid @RequestBody GrantAccessRequest request
    ) {
        log.info("POST /api/auth/grant-access started");
        UserAccessResponse response = authService.grantUserAccess(token, request);
        log.info("POST /api/auth/grant-access completed");
        return response;
    }
}
