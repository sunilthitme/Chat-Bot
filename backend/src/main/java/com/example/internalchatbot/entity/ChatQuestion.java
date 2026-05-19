package com.example.internalchatbot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// Entity mapped to the chat_questions table.
@Entity
@Table(
        name = "chat_questions",
        indexes = {
                @Index(name = "idx_chat_questions_norm_question", columnList = "normalized_question"),
                @Index(name = "idx_chat_questions_norm_keywords", columnList = "normalized_keywords")
        }
)
public class ChatQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(nullable = false, length = 500)
    private String keywords;

    @Column(name = "normalized_question", length = 500)
    private String normalizedQuestion;

    @Column(name = "normalized_keywords", length = 500)
    private String normalizedKeywords;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getNormalizedQuestion() {
        return normalizedQuestion;
    }

    public String getNormalizedKeywords() {
        return normalizedKeywords;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @PrePersist
    void setDefaultCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        refreshSearchFields();
    }

    @PreUpdate
    void updateSearchFields() {
        refreshSearchFields();
    }

    public boolean refreshSearchFields() {
        String nextQuestion = normalize(question);
        String nextKeywords = normalize(keywords);
        boolean changed = !equals(normalizedQuestion, nextQuestion) || !equals(normalizedKeywords, nextKeywords);
        normalizedQuestion = nextQuestion;
        normalizedKeywords = nextKeywords;
        return changed;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
