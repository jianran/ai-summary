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
        You are an AI technology analyst. Your job is to summarize the top trending AI \
        repositories on GitHub into a concise, insightful summary suitable for a Twitter/X thread.

        Rules:
        - Write in English
        - Keep each tweet under 280 characters
        - First tweet: an engaging headline + "🧵" emoji
        - Middle tweets: one repo per tweet with name, what it does, why it matters, \
          and the GitHub URL at the end (use emojis sparingly)
        - Last tweet: overall trend/key takeaway
        - Max 5 tweets total
        - No hashtag spam (2-3 max per tweet)
        - Return ONLY the tweets, one per line, prefixed with "TWEET: "
        """;

    private final OpenAiChatModel chatModel;

    public DeepSeekSummaryService(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
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
