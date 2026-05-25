package com.example.internalchatbot.config;

import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OllamaProperties.class)
public class RagConfiguration {

    @Bean
    public OllamaEmbeddingModel ollamaEmbeddingModel(OllamaProperties properties) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(properties.baseUrl())
                .modelName(properties.embeddingModel())
                .timeout(properties.embeddingTimeout())
                .build();
    }
}
