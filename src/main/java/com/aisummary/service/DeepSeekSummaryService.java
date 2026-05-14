package com.aisummary.service;

import com.aisummary.model.TrendingRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DeepSeekSummaryService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekSummaryService.class);

    private static final String SYSTEM_PROMPT = """
        You are an AI technology analyst. Summarize the top trending AI GitHub repos \
        into a SINGLE tweet under 280 characters.

        Rules:
        - Write in English, single tweet only
        - Start with an engaging hook (e.g. "🔥 Trending AI repos:")
        - List 2-3 top repos with name + one-line description + GitHub URL
        - End with a trend insight or hashtag
        - 1-2 hashtags max
        - Return exactly one line, prefixed with "TWEET: "
        """;

    private final OpenAiChatModel chatModel;

    public DeepSeekSummaryService(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String generateTweetsSummary(String tweetsText) {
        String tweetsPrompt = """
            You are an AI technology analyst. Summarize the following top tweets about AI \
            into a concise weekly digest.

            Rules:
            - Write in English
            - Write 2-3 short paragraphs covering the main themes and highlights
            - Mention the most engaging tweets or ideas
            - Keep it under 500 characters
            - Return plain text only, no prefixes
            """;

        var prompt = new Prompt(List.of(
            new SystemMessage(tweetsPrompt),
            new UserMessage(tweetsText)
        ));

        log.info("Sending tweet summary request to DeepSeek");
        String response = chatModel.call(prompt).getResult().getOutput().getText();
        log.info("Tweet summary generated ({} chars)", response.length());
        return response;
    }

    public String generateSummary(List<TrendingRepo> repos) {
        if (repos.isEmpty()) {
            return "No trending AI repos found today.";
        }

        String reposText = repos.stream()
            .map(r -> String.format(
                "- %s (%s): %s — ⭐%d | Language: %s | Topics: %s",
                r.fullName(),
                r.url(),
                r.description().isEmpty() ? "No description" : r.description(),
                r.stars(),
                r.language(),
                String.join(", ", r.topics())
            ))
            .collect(Collectors.joining("\n"));

        var prompt = new Prompt(List.of(
            new SystemMessage(SYSTEM_PROMPT),
            new UserMessage("Here are today's top trending AI repositories:\n\n" + reposText)
        ));

        log.info("Sending summary request to DeepSeek for {} repos", repos.size());
        String response = chatModel.call(prompt).getResult().getOutput().getText();
        log.info("Summary generated ({} chars)", response.length());
        return response;
    }
}
