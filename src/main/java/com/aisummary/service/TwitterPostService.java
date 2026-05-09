package com.aisummary.service;

import com.aisummary.config.TwitterConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class TwitterPostService {

    private static final Logger log = LoggerFactory.getLogger(TwitterPostService.class);
    private static final int MAX_TWEET_LENGTH = 280;
    private static final String TWEET_URL = "https://api.twitter.com/2/tweets";
    private static final String SEARCH_URL = "https://api.twitter.com/2/tweets/search/recent";
    private static final String OAUTH_SIGNATURE_METHOD = "HMAC-SHA1";
    private static final String OAUTH_VERSION = "1.0";

    private static final String TOKEN_URL = "https://api.twitter.com/oauth2/token";

    private final String consumerKey;
    private final String consumerSecret;
    private final String accessToken;
    private final String accessTokenSecret;
    private final String clientId;
    private final String clientSecret;
    private final String bearerToken;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private volatile String cachedBearerToken;
    private final String userAccessToken;

    public TwitterPostService(TwitterConfig config, ObjectMapper objectMapper) {
        this.consumerKey = config.getConsumerKey();
        this.consumerSecret = config.getConsumerSecret();
        this.accessToken = config.getAccessToken();
        this.accessTokenSecret = config.getAccessTokenSecret();
        this.clientId = config.getClientId();
        this.clientSecret = config.getClientSecret();
        this.bearerToken = config.getBearerToken();
        this.userAccessToken = config.getUserAccessToken();
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    private String getBearerToken() {
        if (bearerToken != null && !bearerToken.isBlank()) {
            return bearerToken;
        }
        if (cachedBearerToken != null) {
            return cachedBearerToken;
        }
        cachedBearerToken = fetchBearerToken();
        return cachedBearerToken;
    }

    private String fetchBearerToken() {
        if (clientId == null || clientSecret == null
                || clientId.isBlank() || clientSecret.isBlank()) {
            log.warn("No Twitter OAuth 2.0 credentials configured");
            return null;
        }
        try {
            String credentials = clientId + ":" + clientSecret;
            String encoded = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            var headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + encoded);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            var request = new HttpEntity<>("grant_type=client_credentials", headers);
            var response = restTemplate.exchange(
                TOKEN_URL, HttpMethod.POST, request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            String token = root.get("access_token").asText();
            log.info("Obtained Bearer Token via OAuth 2.0 App-Only flow");
            return token;
        } catch (Exception e) {
            log.error("Failed to fetch Bearer Token via OAuth 2.0", e);
            return null;
        }
    }

    /**
     * Posts a thread of tweets. Returns the list of tweet IDs posted.
     */
    public List<Long> postThread(String summaryText) {
        List<String> tweets = parseTweets(summaryText);
        List<Long> tweetIds = new ArrayList<>();
        Long inReplyToId = null;

        for (String tweet : tweets) {
            String trimmed = tweet.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.length() > MAX_TWEET_LENGTH) {
                trimmed = trimmed.substring(0, MAX_TWEET_LENGTH - 3) + "...";
            }

            try {
                long tweetId = postTweet(trimmed, inReplyToId);
                tweetIds.add(tweetId);
                inReplyToId = tweetId;
                log.info("Posted tweet {}: {}", tweetId, trimmed.substring(0, Math.min(50, trimmed.length())));
            } catch (Exception e) {
                log.error("Failed to post tweet: {}", trimmed, e);
            }
        }

        log.info("Posted {} tweets in thread", tweetIds.size());
        return tweetIds;
    }

    /**
     * Search recent tweets matching the query using Bearer Token (OAuth 2.0).
     * Returns up to maxResults tweets with their IDs, text, and metrics.
     */
    public List<Map<String, String>> searchRecentTweets(String query, int maxResults) {
        String token = getBearerToken();
        if (token == null) {
            log.warn("No Twitter Bearer Token available — skipping search");
            return List.of();
        }
        try {
            var headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);

            String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                .queryParam("query", query)
                .queryParam("max_results", maxResults)
                .queryParam("tweet.fields", "public_metrics,author_id")
                .build()
                .toString();

            var response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            List<Map<String, String>> results = new ArrayList<>();
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode tweet : data) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", tweet.get("id").asText());
                    entry.put("text", tweet.get("text").asText());
                    entry.put("author_id", tweet.get("author_id").asText(""));
                    JsonNode metrics = tweet.get("public_metrics");
                    if (metrics != null) {
                        int likes = metrics.get("like_count").asInt(0);
                        int retweets = metrics.get("retweet_count").asInt(0);
                        int replies = metrics.get("reply_count").asInt(0);
                        int quotes = metrics.get("quote_count").asInt(0);
                        int engagement = likes + retweets + replies + quotes;
                        entry.put("engagement", String.valueOf(engagement));
                    }
                    results.add(entry);
                }
            }
            log.info("Found {} related tweets for query: {}", results.size(), query);
            return results;
        } catch (Exception e) {
            log.error("Failed to search tweets for query: {}", query, e);
            return List.of();
        }
    }

    /**
     * Reply to a specific tweet. Tries OAuth 2.0 user token first (more permissive),
     * then falls back to OAuth 1.0a.
     */
    public long replyToTweet(long tweetId, String text) throws Exception {
        if (userAccessToken != null && !userAccessToken.isBlank()) {
            try {
                return replyWithOAuth2(tweetId, text);
            } catch (Exception e) {
                log.warn("OAuth 2.0 reply failed, trying OAuth 1.0a: {}", e.getMessage());
            }
        }
        return postTweet(text, tweetId);
    }

    private long replyWithOAuth2(long tweetId, String text) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        Map<String, String> reply = new HashMap<>();
        reply.put("in_reply_to_tweet_id", String.valueOf(tweetId));
        body.put("reply", reply);

        String bodyJson = objectMapper.writeValueAsString(body);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + userAccessToken);

        var request = new HttpEntity<>(bodyJson, headers);
        var response = restTemplate.exchange(
            TWEET_URL, HttpMethod.POST, request, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode data = root.get("data");
        if (data != null && data.has("id")) {
            return Long.parseLong(data.get("id").asText());
        }
        throw new RuntimeException("No tweet ID in response: " + response.getBody());
    }

    /**
     * Quote-tweet another tweet (reposts with your comment on top).
     * Unlike replies, quote tweets don't require prior engagement.
     */
    public long quoteTweet(long tweetId, String text) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("quote_tweet_id", String.valueOf(tweetId));

        String bodyJson = objectMapper.writeValueAsString(body);

        String oauthHeader = buildOAuthHeader();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", oauthHeader);

        var request = new HttpEntity<>(bodyJson, headers);
        var response = restTemplate.exchange(
            TWEET_URL, HttpMethod.POST, request, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode data = root.get("data");
        if (data != null && data.has("id")) {
            return Long.parseLong(data.get("id").asText());
        }
        throw new RuntimeException("No tweet ID in response: " + response.getBody());
    }

    /**
     * Extract the authenticated user's Twitter ID from the OAuth 1.0a access token.
     * Twitter access tokens have the format: {user_id}-{rest}.
     */
    public String getMyUserId() {
        if (accessToken != null && accessToken.contains("-")) {
            return accessToken.substring(0, accessToken.indexOf('-'));
        }
        return null;
    }

    private long postTweet(String text, Long inReplyToId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        if (inReplyToId != null) {
            Map<String, String> reply = new HashMap<>();
            reply.put("in_reply_to_tweet_id", String.valueOf(inReplyToId));
            body.put("reply", reply);
        }

        String bodyJson = objectMapper.writeValueAsString(body);

        String oauthHeader = buildOAuthHeader();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", oauthHeader);

        var request = new HttpEntity<>(bodyJson, headers);
        var response = restTemplate.exchange(
            TWEET_URL, HttpMethod.POST, request, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode data = root.get("data");
        if (data != null && data.has("id")) {
            return Long.parseLong(data.get("id").asText());
        }
        throw new RuntimeException("No tweet ID in response: " + response.getBody());
    }

    private String buildOAuthHeader() throws Exception {
        return buildOAuthHeader("POST", TWEET_URL, Map.of());
    }

    private String buildOAuthHeader(String method, String url, Map<String, String> queryParams) throws Exception {
        String nonce = generateNonce();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        Map<String, String> oauthParams = new TreeMap<>();
        oauthParams.put("oauth_consumer_key", consumerKey);
        oauthParams.put("oauth_nonce", nonce);
        oauthParams.put("oauth_signature_method", OAUTH_SIGNATURE_METHOD);
        oauthParams.put("oauth_timestamp", timestamp);
        oauthParams.put("oauth_token", accessToken);
        oauthParams.put("oauth_version", OAUTH_VERSION);

        String signature = generateSignature(method, url, oauthParams, queryParams);
        oauthParams.put("oauth_signature", signature);

        StringBuilder header = new StringBuilder("OAuth ");
        boolean first = true;
        for (var entry : oauthParams.entrySet()) {
            if (!first) header.append(", ");
            header.append(percentEncode(entry.getKey()))
                  .append("=\"")
                  .append(percentEncode(entry.getValue()))
                  .append("\"");
            first = false;
        }
        return header.toString();
    }

    private String generateSignature(String method, String url,
            Map<String, String> oauthParams, Map<String, String> queryParams) throws Exception {
        Map<String, String> allParams = new TreeMap<>(oauthParams);
        allParams.remove("oauth_signature");
        allParams.putAll(queryParams);

        StringBuilder paramString = new StringBuilder();
        boolean first = true;
        for (var entry : allParams.entrySet()) {
            if (!first) paramString.append("&");
            paramString.append(percentEncode(entry.getKey()))
                       .append("=")
                       .append(percentEncode(entry.getValue()));
            first = false;
        }

        String signatureBase = method + "&"
            + percentEncode(url) + "&"
            + percentEncode(paramString.toString());

        String signingKey = percentEncode(consumerSecret) + "&" + percentEncode(accessTokenSecret);

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] signatureBytes = mac.doFinal(signatureBase.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signatureBytes);
    }

    private String generateNonce() {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        StringBuilder sb = new StringBuilder();
        for (byte b : nonce) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
    }

    private List<String> parseTweets(String summaryText) {
        List<String> tweets = new ArrayList<>();
        for (String line : summaryText.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("TWEET:")) {
                tweets.add(trimmed.substring(6).trim());
            }
        }
        if (tweets.isEmpty()) {
            for (String para : summaryText.split("\n\n")) {
                String trimmed = para.trim();
                if (!trimmed.isEmpty()) tweets.add(trimmed);
            }
        }
        if (tweets.isEmpty()) {
            tweets.add(summaryText.trim());
        }
        return tweets;
    }
}
