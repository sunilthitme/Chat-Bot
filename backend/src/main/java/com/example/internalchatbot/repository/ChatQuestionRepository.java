package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.ChatQuestion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// Repository handles database operations for ChatQuestion records.
public interface ChatQuestionRepository extends JpaRepository<ChatQuestion, Long> {

    @Query("""
            select cq
            from ChatQuestion cq
            where cq.normalizedQuestion = :searchText
               or cq.normalizedKeywords = :searchText
               or cq.normalizedQuestion like concat(:searchText, '%')
               or cq.normalizedKeywords like concat(:searchText, '%')
            order by
                case
                    when cq.normalizedQuestion = :searchText then 0
                    when cq.normalizedKeywords = :searchText then 1
                    when cq.normalizedQuestion like concat(:searchText, '%') then 2
                    else 3
                end,
                cq.id asc
            """)
    List<ChatQuestion> searchExactOrPrefix(@Param("searchText") String searchText, Pageable pageable);
}
