package com.example.internalchatbot.ai.llm.github;

import java.util.List;

public record GitHubModelsChatResponse(
        List<Choice> choices
) {

    public String firstMessageContent() {
        if (choices == null || choices.isEmpty() || choices.getFirst().message() == null) {
            return "";
        }
        String content = choices.getFirst().message().content();
        return content == null ? "" : content;
    }

    public record Choice(
            GitHubModelsMessage message
    ) {
    }
}
