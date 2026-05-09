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
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    private String resolveToken() {
        if (githubToken != null && !githubToken.isBlank()) {
            return githubToken;
        }
        Path hostsFile = Path.of(System.getProperty("user.home"), ".config/gh/hosts.yml");
        if (!Files.exists(hostsFile)) {
            log.debug("No gh hosts.yml found at {}", hostsFile);
            return null;
        }
        try {
            String content = Files.readString(hostsFile);
            Map<String, Object> config = new Yaml().load(content);
            @SuppressWarnings("unchecked")
            Map<String, Object> ghEntry = (Map<String, Object>) config.get("github.com");
            if (ghEntry != null) {
                String token = (String) ghEntry.get("oauth_token");
                if (token != null && !token.isBlank()) {
                    log.info("Using GitHub token from gh CLI config");
                    return token;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read gh CLI config: {}", e.getMessage());
        }
        return null;
    }

    public List<TrendingRepo> fetchTopAiRepos() {
        String weekAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString().substring(0, 10);
        String query = String.format(
            "topic:machine-learning,topic:deep-learning,topic:llm,topic:large-language-model,"
            + "topic:generative-ai,topic:ai-agent,topic:rag,topic:transformer,"
            + "topic:openai,topic:gpt,topic:chatgpt,topic:claude,topic:gemini,"
            + "topic:llama,topic:mistral,topic:deepseek,topic:anthropic+"
            + "stars:>50+"
            + "pushed:>%s",
            weekAgo
        );
        String url = String.format("%s?q=%s&sort=updated&order=desc&per_page=%d",
            SEARCH_URL, query, searchLimit);

        String token = resolveToken();

        var headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
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
