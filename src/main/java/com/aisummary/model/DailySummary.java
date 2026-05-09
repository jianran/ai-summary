package com.aisummary.model;

import java.time.Instant;
import java.util.List;

public record DailySummary(
    Instant generatedAt,
    List<TrendingRepo> topRepos,
    String aiSummary,
    String tweetThread
) {}
