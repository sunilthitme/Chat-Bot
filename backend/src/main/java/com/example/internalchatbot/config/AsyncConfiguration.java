package com.example.internalchatbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class AsyncConfiguration implements WebMvcConfigurer {

    private final Duration requestTimeout;

    public AsyncConfiguration(@Value("${chat.request-timeout:120s}") Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Bean(name = "chatTaskExecutor")
    public AsyncTaskExecutor chatTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("chat-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(64);
        return executor;
    }

    @Bean(name = "ingestionTaskExecutor")
    public AsyncTaskExecutor ingestionTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("ingest-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(6);
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(requestTimeout.toMillis());
        configurer.setTaskExecutor(chatTaskExecutor());
    }
}
