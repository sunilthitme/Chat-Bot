package com.example.internalchatbot.ai.llm.github;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GitHubModelsChatRequest(
        String model,
        List<GitHubModelsMessage> messages,
        double temperature,
        @JsonProperty("max_tokens")
        int maxTokens,
        boolean stream
) {
}
