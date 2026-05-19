package com.example.internalchatbot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "chat_question_tokens",
        indexes = {
                @Index(name = "idx_chat_question_tokens_token", columnList = "token"),
                @Index(name = "idx_chat_question_tokens_question", columnList = "question_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_chat_question_tokens_question_token", columnNames = {"question_id", "token"})
        }
)
public class ChatQuestionToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(nullable = false, length = 80)
    private String token;

    public ChatQuestionToken() {
    }

    public ChatQuestionToken(Long questionId, String token) {
        this.questionId = questionId;
        this.token = token;
    }

    public Long getId() {
        return id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getToken() {
        return token;
    }
}
