package com.example.internalchatbot.controller;

import com.example.internalchatbot.dto.ClientLogRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// REST controller lets the frontend write important client errors to system.out.logs.
@RestController
@RequestMapping("/api/logs")
@CrossOrigin(origins = "http://localhost:4200")
public class ClientLogController {

    private static final Logger log = LoggerFactory.getLogger(ClientLogController.class);

    @PostMapping("/client-error")
    public void logClientError(@Valid @RequestBody ClientLogRequest request) {
        log.error("Frontend error reported. message={}", request.getMessage());
    }
}
