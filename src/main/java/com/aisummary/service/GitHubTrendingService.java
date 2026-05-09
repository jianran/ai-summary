package com.aisummary.service;

import com.aisummary.model.TrendingRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class GitHubTrendingService {

    private static final Logger log = LoggerFactory.getLogger(GitHubTrendingService.class);

    private static final String SEARCH_URL = "https://api.github.com/search/repositories";

    @Value("${github.token:#{null}}")
    private String githubToken;

    @Value("${github.search.limit:10}")
    private int searchLimit;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GitHubTrendingService(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    public List<TrendingRepo> fetchTopAiRepos() {
        String yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString().substring(0, 10);
        String query = String.format(
            "topic:machine-learning+topic:deep-learning+topic:llm+topic:large-language-model+"
            + "topic:generative-ai+topic:ai-agent+topic:rag+topic:transformer+"
            + "pushed:>%s",
            yesterday
        );
        String url = String.format("%s?q=%s&sort=stars&order=desc&per_page=%d",
            SEARCH_URL, query, searchLimit);

        var headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (githubToken != null && !githubToken.isBlank()) {
            headers.set("Authorization", "Bearer " + githubToken);
        }
        headers.set("User-Agent", "ai-summary-bot");

        try {
            var response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (response.getBody() == null) {
                log.warn("Empty response from GitHub search API");
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                log.warn("No items in GitHub search response");
                return List.of();
            }

            List<TrendingRepo> repos = new ArrayList<>();
            for (JsonNode item : items) {
                repos.add(toTrendingRepo(item));
            }

            log.info("Fetched {} trending AI repos", repos.size());
            return repos;
        } catch (Exception e) {
            log.error("Failed to fetch trending repos from GitHub", e);
            return List.of();
        }
    }

    private TrendingRepo toTrendingRepo(JsonNode item) {
        List<String> topics = new ArrayList<>();
        JsonNode topicsNode = item.get("topics");
        if (topicsNode != null && topicsNode.isArray()) {
            for (JsonNode topic : topicsNode) {
                topics.add(topic.asText());
            }
        }

        return new TrendingRepo(
            item.get("name").asText(""),
            item.get("full_name").asText(""),
            item.get("description").asText(""),
            item.get("html_url").asText(""),
            item.get("stargazers_count").asInt(0),
            item.get("language").asText(""),
            topics
        );
    }
}
