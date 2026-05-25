package com.example.internalchatbot.ai.llm;

import com.example.internalchatbot.ai.llm.provider.AiProvider;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class LlmService {

    private final AiProvider aiProvider;

    public LlmService(AiProvider aiProvider) {
        this.aiProvider = aiProvider;
    }

    public boolean isEnabled() {
        return aiProvider.isEnabled();
    }

    public String generateResponse(String prompt) {
        return aiProvider.generateResponse(prompt);
    }

    public String generateResponse(String prompt, double responseTemperature) {
        return aiProvider.generateResponse(prompt, responseTemperature);
    }

    public void streamResponse(String prompt, Consumer<String> onToken) {
        aiProvider.streamResponse(prompt, onToken);
    }

    public String providerName() {
        return aiProvider.providerName();
    }
}
