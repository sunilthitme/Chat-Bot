package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.ChatQuestionToken;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatQuestionTokenRepository extends JpaRepository<ChatQuestionToken, Long> {

    boolean existsByQuestionId(Long questionId);

    @Modifying
    void deleteByQuestionId(Long questionId);

    @Query("""
            select token.questionId
            from ChatQuestionToken token
            where token.token in :tokens
            group by token.questionId
            order by count(token.id) desc, token.questionId asc
            """)
    List<Long> findRankedQuestionIds(@Param("tokens") Collection<String> tokens, Pageable pageable);
}
