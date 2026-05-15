package com.example.internalchatbot.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;

@Service
public class UrlReaderService {

    private final UrlValidationService urlValidationService;
    private final AuthenticatedUrlReaderService authenticatedUrlReaderService;

    public UrlReaderService(
            UrlValidationService urlValidationService,
            AuthenticatedUrlReaderService authenticatedUrlReaderService
    ) {
        this.urlValidationService = urlValidationService;
        this.authenticatedUrlReaderService = authenticatedUrlReaderService;
    }

    public String read(String url, boolean loginRequired) {
        URI uri = urlValidationService.validate(url);
        if (loginRequired) {
            return authenticatedUrlReaderService.readAfterLogin(uri);
        }
        return readStatic(uri);
    }

    private String readStatic(URI uri) {
        try {
            Document document = Jsoup.connect(uri.toString())
                    .timeout(15_000)
                    .userAgent("InternalChatbot/1.0")
                    .get();
            document.select("script,style,noscript,svg").remove();
            return document.text();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read URL content", ex);
        }
    }
}
