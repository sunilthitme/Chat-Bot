package com.example.internalchatbot.dto;

import com.example.internalchatbot.entity.UserRole;

// Response DTO returned after admin grants access.
public class UserAccessResponse {

    private String email;
    private UserRole role;
    private boolean active;

    public UserAccessResponse(String email, UserRole role, boolean active) {
        this.email = email;
        this.role = role;
        this.active = active;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
