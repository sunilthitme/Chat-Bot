package com.example.internalchatbot.config;

import com.example.internalchatbot.entity.Announcement;
import com.example.internalchatbot.entity.AppUser;
import com.example.internalchatbot.entity.UserRole;
import com.example.internalchatbot.repository.AnnouncementRepository;
import com.example.internalchatbot.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

// Adds demo users and an initial announcement for local development.
@Component
public class BootstrapDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDataLoader.class);

    private final AppUserRepository appUserRepository;
    private final AnnouncementRepository announcementRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public BootstrapDataLoader(
            AppUserRepository appUserRepository,
            AnnouncementRepository announcementRepository,
            BCryptPasswordEncoder passwordEncoder
    ) {
        this.appUserRepository = appUserRepository;
        this.announcementRepository = announcementRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        log.info("Bootstrap data setup started");
        createUserIfMissing("admin@td.com", "admin123", UserRole.ADMIN);
        createUserIfMissing("user@td.com", "user123", UserRole.USER);
        createAnnouncementIfMissing();
        log.info("Bootstrap data setup completed");
    }

    private void createUserIfMissing(String email, String password, UserRole role) {
        if (appUserRepository.findByEmailIgnoreCase(email).isPresent()) {
            log.info("Demo user already exists. email={}, role={}", email, role);
            return;
        }

        AppUser appUser = new AppUser();
        appUser.setEmail(email);
        appUser.setPasswordHash(passwordEncoder.encode(password));
        appUser.setRole(role);
        appUser.setActive(true);
        appUserRepository.save(appUser);
        log.info("Demo user created. email={}, role={}", email, role);
    }

    private void createAnnouncementIfMissing() {
        if (announcementRepository.count() > 0) {
            log.info("Announcement already exists");
            return;
        }

        Announcement announcement = new Announcement();
        announcement.setMessage("Welcome to the Internal Chatbot. Ask about RITM, password reset, or incidents.");
        announcement.setActive(true);
        announcementRepository.save(announcement);
        log.info("Default announcement created");
    }
}
