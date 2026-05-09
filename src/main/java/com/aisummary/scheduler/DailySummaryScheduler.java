package com.aisummary.scheduler;

import com.aisummary.service.DeepSeekSummaryService;
import com.aisummary.service.GitHubTrendingService;
import com.aisummary.service.TwitterPostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class DailySummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryScheduler.class);

    private final GitHubTrendingService githubService;
    private final DeepSeekSummaryService summaryService;
    private final TwitterPostService twitterService;

    @Value("${app.daily-post.enabled:false}")
    private boolean enabled;

    public DailySummaryScheduler(
            GitHubTrendingService githubService,
            DeepSeekSummaryService summaryService,
            TwitterPostService twitterService) {
        this.githubService = githubService;
        this.summaryService = summaryService;
        this.twitterService = twitterService;
    }

    /**
     * Runs daily at 9:00 AM. Fetches top AI repos, summarizes via DeepSeek, posts to X.
     */
    @Scheduled(cron = "${app.daily-post.cron:0 0 9 * * *}")
    public void postDailySummary() {
        if (!enabled) {
            log.info("Daily post is disabled. Set app.daily-post.enabled=true to enable.");
            return;
        }

        log.info("=== Starting daily AI summary job ===");
        try {
            var repos = githubService.fetchTopAiRepos();
            log.info("Fetched {} repos", repos.size());

            String summary = summaryService.generateSummary(repos);
            log.info("DeepSeek summary:\n{}", summary);

            var tweetIds = twitterService.postThread(summary);
            log.info("=== Daily job complete — posted {} tweets: {} ===", tweetIds.size(), tweetIds);

            searchAndEngage(summary, tweetIds);
        } catch (Exception e) {
            log.error("Daily summary job failed", e);
        }
    }

    private static final String AI_SEARCH_KEYWORDS =
        "AI agent OR large language model OR machine learning OR "
        + "deep learning OR generative AI OR LLM OR AI tool OR "
        + "open source AI OR AI framework OR LLMOps OR AI deployment";

    private void searchAndEngage(String summary, List<Long> threadTweetIds) {
        if (threadTweetIds.isEmpty()) return;
        try {
            var relatedTweets = twitterService.searchRecentTweets(AI_SEARCH_KEYWORDS, 100);
            if (relatedTweets.isEmpty()) {
                log.info("No related AI tweets found");
                return;
            }

            String myUserId = twitterService.getMyUserId();

            var othersTweets = relatedTweets.stream()
                .filter(t -> !t.getOrDefault("author_id", "").equals(myUserId))
                .sorted(Comparator.comparingInt(
                    t -> -Integer.parseInt(t.getOrDefault("engagement", "0"))))
                .limit(3)
                .toList();

            if (othersTweets.isEmpty()) {
                log.info("No related tweets from other users found");
                return;
            }

            String replyText = buildReplyText(summary);
            for (var tweet : othersTweets) {
                try {
                    long replyId = twitterService.replyToTweet(
                        Long.parseLong(tweet.get("id")), replyText);
                    log.info("Replied to tweet {} (eng: {}) -> {}",
                        tweet.get("id"), tweet.get("engagement"), replyId);
                    Thread.sleep(2000);
                } catch (Exception e) {
                    log.error("Failed to reply to tweet {} — {}", tweet.get("id"), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Search and engage failed", e);
        }
    }

    private String buildReplyText(String summary) {
        StringBuilder sb = new StringBuilder("Trending AI repos on GitHub this week:\n");
        for (String line : summary.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("TWEET:") && trimmed.contains("github.com/")) {
                String tweet = trimmed.substring(6).trim();
                int idx = tweet.indexOf("github.com/");
                sb.append("\n").append(tweet.substring(idx));
            }
        }
        if (sb.length() > 280) sb.setLength(277);
        return sb.toString().trim();
    }
}
