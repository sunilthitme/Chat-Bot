package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.ChatQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// Repository handles database operations for ChatQuestion records.
public interface ChatQuestionRepository extends JpaRepository<ChatQuestion, Long> {

    @Query("""
            select cq
            from ChatQuestion cq
            where lower(cq.question) like lower(concat('%', :searchText, '%'))
               or lower(cq.keywords) like lower(concat('%', :searchText, '%'))
            order by cq.id asc
            """)
    List<ChatQuestion> searchByQuestionOrKeywords(@Param("searchText") String searchText);
}
