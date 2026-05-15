package com.example.internalchatbot.dto;

import java.util.ArrayList;
import java.util.List;

// Response DTO returned to the Angular frontend.
public class ChatResponse {

    private String sessionId;
    private String reply;
    private boolean privateMode;
    private List<SourceReference> sources = new ArrayList<>();

    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public ChatResponse(String sessionId, String reply, boolean privateMode, List<SourceReference> sources) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.privateMode = privateMode;
        this.sources = sources == null ? new ArrayList<>() : sources;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

    public List<SourceReference> getSources() {
        return sources;
    }

    public void setSources(List<SourceReference> sources) {
        this.sources = sources;
    }
}
