package com.example.internalchatbot.service;

import com.example.internalchatbot.dto.AnnouncementRequest;
import com.example.internalchatbot.dto.AnnouncementResponse;
import com.example.internalchatbot.entity.Announcement;
import com.example.internalchatbot.entity.UserRole;
import com.example.internalchatbot.repository.AnnouncementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Service contains announcement read and admin update logic.
@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

    private final AnnouncementRepository announcementRepository;
    private final AuthService authService;

    public AnnouncementService(AnnouncementRepository announcementRepository, AuthService authService) {
        this.announcementRepository = announcementRepository;
        this.authService = authService;
    }

    public AnnouncementResponse getActiveAnnouncement() {
        log.info("Fetching active announcement");
        String message = announcementRepository.findFirstByActiveTrueOrderByCreatedAtDesc()
                .map(Announcement::getMessage)
                .orElse("");
        log.info("Active announcement fetched. hasMessage={}", !message.isBlank());
        return new AnnouncementResponse(message);
    }

    public AnnouncementResponse addAnnouncement(String token, AnnouncementRequest request) {
        authService.requireRole(token, UserRole.ADMIN);
        log.info("Add announcement started");

        Announcement announcement = new Announcement();
        announcement.setMessage(request.getMessage().trim());
        announcement.setActive(true);
        Announcement savedAnnouncement = announcementRepository.save(announcement);

        log.info("Add announcement completed. announcementId={}", savedAnnouncement.getId());
        return new AnnouncementResponse(savedAnnouncement.getMessage());
    }
}
