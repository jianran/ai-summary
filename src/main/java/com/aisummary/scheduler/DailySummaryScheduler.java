package com.aisummary.scheduler;

import com.aisummary.service.DeepSeekSummaryService;
import com.aisummary.service.GitHubTrendingService;
import com.aisummary.service.TwitterPostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

    private static final String AI_KEYWORDS =
        "AI agent OR large language model OR machine learning OR "
        + "deep learning OR generative AI OR LLM OR AI tool OR "
        + "open source AI OR AI framework OR LLMOps OR AI deployment";

    @Scheduled(cron = "${app.daily-post.cron:0 0 9 * * *}")
    public void postDailySummary() {
        if (!enabled) {
            log.info("Daily post is disabled");
            return;
        }

        log.info("=== Starting daily AI summary job ===");
        try {
            var repos = githubService.fetchTopAiRepos();
            log.info("Fetched {} repos", repos.size());

            String summary = summaryService.generateSummary(repos);
            log.info("DeepSeek summary:\n{}", summary);

            var tweetIds = twitterService.postThread(summary);
            log.info("Posted {} tweets: {}", tweetIds.size(), tweetIds);

            postEngagementRoundup(tweetIds);
            log.info("=== Daily job complete ===");
        } catch (Exception e) {
            log.error("Daily summary job failed", e);
        }
    }

    private void postEngagementRoundup(List<Long> threadTweetIds) {
        if (threadTweetIds.isEmpty()) return;
        try {
            String endTime = Instant.now().minus(30, ChronoUnit.SECONDS).toString();
            String startTime = Instant.now().minus(7, ChronoUnit.DAYS).toString();

            var tweets = twitterService.searchTweets(AI_KEYWORDS, 500, startTime, endTime);
            if (tweets.isEmpty()) { log.info("No AI tweets found for roundup"); return; }

            String myUserId = twitterService.getMyUserId();

            var top5 = tweets.stream()
                .filter(t -> !t.getOrDefault("author_id", "").equals(myUserId))
                .sorted(Comparator.comparingInt(
                    t -> -Integer.parseInt(t.getOrDefault("engagement", "0"))))
                .limit(5)
                .toList();

            if (top5.isEmpty()) { log.info("No others' tweets for roundup"); return; }

            StringBuilder sb = new StringBuilder("Top AI conversations this week:\n\n");
            for (int i = 0; i < top5.size(); i++) {
                var t = top5.get(i);
                sb.append(i + 1).append(". x.com/i/status/").append(t.get("id"))
                  .append("  (").append(t.get("engagement")).append(" engagements)\n");
            }
            if (sb.length() > 280) sb.setLength(277);

            long replyId = twitterService.replyToThread(threadTweetIds.getFirst(), sb.toString());
            log.info("Posted engagement roundup reply {} with top {} tweets", replyId, top5.size());
        } catch (Exception e) {
            log.error("Engagement roundup failed", e);
        }
    }
}
