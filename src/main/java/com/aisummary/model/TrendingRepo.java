package com.aisummary.model;

import java.util.List;

public record TrendingRepo(
    String name,
    String fullName,
    String description,
    String url,
    int stars,
    String language,
    List<String> topics
) {}
