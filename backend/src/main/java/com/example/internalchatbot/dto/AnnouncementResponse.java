package com.example.internalchatbot.dto;

// Response DTO returned to show the active announcement.
public class AnnouncementResponse {

    private String message;

    public AnnouncementResponse(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
