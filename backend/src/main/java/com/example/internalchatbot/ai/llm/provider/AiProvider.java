package com.example.internalchatbot.ai.llm.provider;

import java.util.function.Consumer;

public interface AiProvider {

    boolean isEnabled();

    String generateResponse(String prompt);

    String generateResponse(String prompt, double temperature);

    void streamResponse(String prompt, Consumer<String> onToken);

    String providerName();
}
