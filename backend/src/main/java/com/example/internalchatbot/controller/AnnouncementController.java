package com.example.internalchatbot.controller;

import com.example.internalchatbot.dto.AnnouncementRequest;
import com.example.internalchatbot.dto.AnnouncementResponse;
import com.example.internalchatbot.service.AnnouncementService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// REST controller exposes announcement APIs.
@RestController
@RequestMapping("/api/announcements")
@CrossOrigin(origins = "http://localhost:4200")
public class AnnouncementController {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementController.class);

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping("/active")
    public AnnouncementResponse getActiveAnnouncement() {
        log.info("GET /api/announcements/active started");
        AnnouncementResponse response = announcementService.getActiveAnnouncement();
        log.info("GET /api/announcements/active completed");
        return response;
    }

    @PostMapping
    public AnnouncementResponse addAnnouncement(
            @RequestHeader("X-User-Token") String token,
            @Valid @RequestBody AnnouncementRequest request
    ) {
        log.info("POST /api/announcements started");
        AnnouncementResponse response = announcementService.addAnnouncement(token, request);
        log.info("POST /api/announcements completed");
        return response;
    }
}
