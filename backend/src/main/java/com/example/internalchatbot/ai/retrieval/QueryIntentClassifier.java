package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.ai.vectorstore.RetrievalFilter;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class QueryIntentClassifier {

    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9_./-]+");
    private static final Set<String> CODE_TOKENS = Set.of(
            "code", "java", "class", "method", "controller", "repository", "bean",
            "requestmapping", "getmapping", "postmapping", "spring", "springboot"
    );
    private static final Set<String> CONFIG_TOKENS = Set.of(
            "configuration", "config", "properties", "yaml", "yml", "application.properties", "application.yml"
    );
    private static final Set<String> API_TOKENS = Set.of(
            "api", "endpoint", "rest", "controller", "requestmapping", "getmapping", "postmapping"
    );
    private static final Set<String> RESUME_TOKENS = Set.of(
            "resume", "cv", "education", "degree", "bachelor", "bachelors", "experience",
            "skills", "certification", "certifications", "project", "projects", "college", "university"
    );

    public QueryIntent classify(String question) {
        String normalized = normalize(question);
        Set<String> tokens = tokens(normalized);

        double codeScore = score(tokens, normalized, CODE_TOKENS, "spring boot", "give java code", "show code", "api logic");
        double configScore = score(tokens, normalized, CONFIG_TOKENS, "spring boot configuration", "application properties", "application yml");
        double apiScore = score(tokens, normalized, API_TOKENS, "find api logic", "api logic", "rest endpoint");
        double resumeScore = score(tokens, normalized, RESUME_TOKENS, "bachelor degree", "completed bachelor", "work experience");

        if (resumeScore >= Math.max(codeScore, Math.max(configScore, apiScore))) {
            return new QueryIntent(
                    "resume",
                    Math.min(0.98, resumeScore),
                    RetrievalFilter.boostOnly(Set.of("resume", "pdf"), Set.of("text"), Set.of("resume", "education", "experience"))
            );
        }
        if (codeScore >= configScore && codeScore >= apiScore && codeScore > 0) {
            return new QueryIntent(
                    "code",
                    Math.min(0.98, codeScore),
                    RetrievalFilter.boostOnly(Set.of("code"), Set.of("java"), Set.of("spring-boot", "api"))
            );
        }
        if (configScore >= apiScore && configScore > 0) {
            return new QueryIntent(
                    "configuration",
                    Math.min(0.98, configScore),
                    RetrievalFilter.boostOnly(Set.of("code"), Set.of("properties", "yaml"), Set.of("spring-boot"))
            );
        }
        if (apiScore > 0) {
            return new QueryIntent(
                    "api",
                    Math.min(0.98, apiScore),
                    RetrievalFilter.boostOnly(Set.of("code"), Set.of("java"), Set.of("api", "spring-boot"))
            );
        }
        return new QueryIntent("general", 0, RetrievalFilter.none());
    }

    private double score(Set<String> tokens, String normalized, Set<String> exactTokens, String... phrases) {
        double score = 0;
        long tokenMatches = exactTokens.stream().filter(tokens::contains).count();
        score += Math.min(0.65, tokenMatches * 0.18);
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                score += 0.35;
            }
        }
        return score;
    }

    private Set<String> tokens(String normalized) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Arrays.stream(WORD_SPLIT.split(normalized))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .forEach(tokens::add);
        return tokens;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
