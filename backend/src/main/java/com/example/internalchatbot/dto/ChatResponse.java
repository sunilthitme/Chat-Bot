package com.example.internalchatbot.dto;

import java.util.List;

// Response DTO returned to the Angular frontend.
public class ChatResponse {

    private String reply;
    private boolean privateMode;
    private List<SourceReferenceResponse> sources = List.of();

    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public ChatResponse(String reply, boolean privateMode) {
        this(reply, privateMode, List.of());
    }

    public ChatResponse(String reply, boolean privateMode, List<SourceReferenceResponse> sources) {
        this.reply = reply;
        this.privateMode = privateMode;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
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

    public List<SourceReferenceResponse> getSources() {
        return sources;
    }

    public void setSources(List<SourceReferenceResponse> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
