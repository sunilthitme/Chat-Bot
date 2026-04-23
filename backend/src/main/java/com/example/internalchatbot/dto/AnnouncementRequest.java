package com.example.internalchatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Request DTO used by admin to add a new announcement.
public class AnnouncementRequest {

    @NotBlank(message = "Announcement message is required")
    @Size(max = 500, message = "Announcement message must be 500 characters or less")
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
