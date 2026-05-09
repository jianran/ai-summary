package com.aisummary.scheduler;

import com.aisummary.service.DeepSeekSummaryService;
import com.aisummary.service.GitHubTrendingService;
import com.aisummary.service.TwitterPostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
            var relatedTweets = twitterService.searchRecentTweets(AI_SEARCH_KEYWORDS, 10);
            if (relatedTweets.isEmpty()) {
                log.info("No related AI tweets found");
                return;
            }

            var topTweets = relatedTweets.stream().limit(3).toList();
            StringBuilder sb = new StringBuilder("Related conversations happening right now:\n");
            for (var tweet : topTweets) {
                String snippet = tweet.get("text");
                if (snippet.length() > 80) snippet = snippet.substring(0, 77) + "...";
                sb.append("\n\ntwitter.com/i/status/").append(tweet.get("id"));
                sb.append("\n").append(snippet);
            }
            if (sb.length() > 280) {
                sb.setLength(277);
                sb.append("...");
            }

            long replyId = twitterService.replyToTweet(threadTweetIds.getFirst(), sb.toString());
            log.info("Posted engagement reply {} to thread", replyId);
        } catch (Exception e) {
            log.error("Search and engage failed", e);
        }
    }
}
