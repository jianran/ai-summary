package com.aisummary.scheduler;

import com.aisummary.model.TrendingRepo;
import com.aisummary.service.DeepSeekSummaryService;
import com.aisummary.service.GitHubTrendingService;
import com.aisummary.service.TwitterPostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

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

            searchAndEngage(repos);
        } catch (Exception e) {
            log.error("Daily summary job failed", e);
        }
    }

    private void searchAndEngage(List<TrendingRepo> repos) {
        if (repos.isEmpty()) return;
        try {
            String query = repos.stream()
                .limit(3)
                .map(TrendingRepo::name)
                .collect(Collectors.joining(" OR "));

            var relatedTweets = twitterService.searchRecentTweets(query, 10);
            if (relatedTweets.isEmpty()) {
                log.info("No related tweets found for query: {}", query);
                return;
            }

            var topTweets = relatedTweets.stream().limit(3).toList();
            String replyText = buildEngagementReply(repos);
            for (var tweet : topTweets) {
                try {
                    long replyId = twitterService.replyToTweet(
                        Long.parseLong(tweet.get("id")), replyText);
                    log.info("Replied to tweet {} with id {}", tweet.get("id"), replyId);
                    Thread.sleep(2000);
                } catch (Exception e) {
                    log.error("Failed to reply to tweet {}", tweet.get("id"), e);
                }
            }
        } catch (Exception e) {
            log.error("Search and engage failed", e);
        }
    }

    private String buildEngagementReply(List<TrendingRepo> repos) {
        var top = repos.stream().limit(3).toList();
        StringBuilder sb = new StringBuilder("Top trending AI repos right now:\n\n");
        for (int i = 0; i < top.size(); i++) {
            var r = top.get(i);
            sb.append(i + 1).append(". ").append(r.fullName())
              .append(" — ").append(r.url()).append("\n");
        }
        if (sb.length() > 270) {
            sb.setLength(267);
            sb.append("...");
        }
        return sb.toString();
    }
}
