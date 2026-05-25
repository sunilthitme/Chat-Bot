package com.example.internalchatbot.config;

import com.example.internalchatbot.ai.llm.github.GitHubModelsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GitHubModelsProperties.class)
public class AiProviderConfiguration {
}
