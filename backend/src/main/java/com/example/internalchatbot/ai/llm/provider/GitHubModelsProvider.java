package com.example.internalchatbot.ai.llm.provider;

import com.example.internalchatbot.ai.llm.github.GitHubModelsProperties;
import com.example.internalchatbot.ai.llm.github.GitHubModelsService;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.function.Consumer;

@Service
public class GitHubModelsProvider implements AiProvider {

    private static final String PROVIDER_NAME = "GitHub Models";

    private final GitHubModelsProperties properties;
    private final GitHubModelsService gitHubModelsService;

    public GitHubModelsProvider(
            GitHubModelsProperties properties,
            GitHubModelsService gitHubModelsService
    ) {
        this.properties = properties;
        this.gitHubModelsService = gitHubModelsService;
    }

    @Override
    public boolean isEnabled() {
        return properties.providerEnabled();
    }

    @Override
    public String generateResponse(String prompt) {
        return generateResponse(prompt, properties.temperature());
    }

    @Override
    public String generateResponse(String prompt, double temperature) {
        ensureEnabled();
        return gitHubModelsService.complete(prompt, temperature);
    }

    @Override
    public void streamResponse(String prompt, Consumer<String> onToken) {
        ensureEnabled();
        gitHubModelsService.stream(prompt, properties.temperature(), onToken);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new RestClientException("GitHub Models provider is disabled or token is not configured");
        }
    }
}
