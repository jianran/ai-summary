package com.aisummary.scheduler;

import com.aisummary.service.DiscordMessageService;
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

@Component
public class DailySummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryScheduler.class);

    private final GitHubTrendingService githubService;
    private final DeepSeekSummaryService summaryService;
    private final TwitterPostService twitterService;
    private final DiscordMessageService discordService;

    @Value("${app.daily-post.enabled:false}")
    private boolean enabled;

    public DailySummaryScheduler(
            GitHubTrendingService githubService,
            DeepSeekSummaryService summaryService,
            TwitterPostService twitterService,
            DiscordMessageService discordService) {
        this.githubService = githubService;
        this.summaryService = summaryService;
        this.twitterService = twitterService;
        this.discordService = discordService;
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

            twitterService.postThread(summary);

            postEngagementRoundup();
            log.info("=== Daily job complete ===");
        } catch (Exception e) {
            log.error("Daily summary job failed", e);
        }
    }

    private void postEngagementRoundup() {
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

            StringBuilder tweetList = new StringBuilder("Top AI tweets this week by engagement:\n\n");
            for (int i = 0; i < top5.size(); i++) {
                var t = top5.get(i);
                tweetList.append(i + 1).append(". ").append(t.get("text"))
                  .append(" — by @").append(t.get("author_id"))
                  .append(" | ").append(t.get("engagement")).append(" engagements\n");
                tweetList.append("   https://x.com/i/status/").append(t.get("id")).append("\n\n");
            }

            String summary = summaryService.generateTweetsSummary(tweetList.toString());
            log.info("DeepSeek summary of roundup:\n{}", summary);

            String content = "Top AI conversations this week:\n\n" + summary;
            if (content.length() > 2000) content = content.substring(0, 1997) + "...";

            discordService.sendRoundup(content);
            log.info("Sent engagement roundup to Discord with top {} tweets", top5.size());
        } catch (Exception e) {
            log.error("Engagement roundup failed", e);
        }
    }
}
