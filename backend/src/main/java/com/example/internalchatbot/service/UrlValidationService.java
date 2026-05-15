package com.example.internalchatbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UrlValidationService {

    private final Set<String> allowedDomains;

    public UrlValidationService(@Value("${security.allowed-url-domains}") String allowedDomains) {
        this.allowedDomains = Arrays.stream(allowedDomains.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    public URI validate(String url) {
        URI uri = URI.create(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only http and https URLs are supported");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host is required");
        }

        String normalizedHost = host.toLowerCase();
        boolean allowed = allowedDomains.stream()
                .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
        if (!allowed) {
            throw new IllegalArgumentException("URL domain is not allowed: " + normalizedHost);
        }

        return uri;
    }
}
