package com.example.internalchatbot.dto;

// Response DTO returned to the Angular frontend.
public class ChatResponse {

    private String reply;
    private boolean privateMode;

    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public ChatResponse(String reply, boolean privateMode) {
        this.reply = reply;
        this.privateMode = privateMode;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public boolean isPrivateMode() {
        return privateMode;
    }

    public void setPrivateMode(boolean privateMode) {
        this.privateMode = privateMode;
    }

}
