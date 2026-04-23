package com.example.internalchatbot.dto;

import com.example.internalchatbot.entity.UserRole;

// Response DTO returned after successful login.
public class LoginResponse {

    private String token;
    private String email;
    private UserRole role;

    public LoginResponse(String token, String email, UserRole role) {
        this.token = token;
        this.email = email;
        this.role = role;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }
}
