package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// Repository handles database operations for announcements.
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Optional<Announcement> findFirstByActiveTrueOrderByCreatedAtDesc();
}
