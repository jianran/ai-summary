package com.aisummary.service;

import com.aisummary.config.TwitterConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
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
    private static final String TOKEN_URL = "https://api.twitter.com/oauth2/token";
    private static final String OAUTH_SIGNATURE_METHOD = "HMAC-SHA1";
    private static final String OAUTH_VERSION = "1.0";

    private final String consumerKey;
    private final String consumerSecret;
    private final String accessToken;
    private final String accessTokenSecret;
    private final String bearerToken;
    private final String clientId;
    private final String clientSecret;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private volatile String cachedBearerToken;

    public TwitterPostService(TwitterConfig config, ObjectMapper objectMapper) {
        this.consumerKey = config.getConsumerKey();
        this.consumerSecret = config.getConsumerSecret();
        this.accessToken = config.getAccessToken();
        this.accessTokenSecret = config.getAccessTokenSecret();
        this.bearerToken = config.getBearerToken();
        this.clientId = config.getClientId();
        this.clientSecret = config.getClientSecret();
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    // ---- Posting ----

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

    public long replyToThread(long threadTweetId, String text) throws Exception {
        return postTweet(text, threadTweetId);
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

        var response = restTemplate.exchange(TWEET_URL, HttpMethod.POST, new HttpEntity<>(bodyJson, headers), String.class);
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        if (data != null && data.has("id")) return Long.parseLong(data.get("id").asText());
        throw new RuntimeException("No tweet ID in response: " + response.getBody());
    }

    // ---- Search ----

    public List<Map<String, String>> searchTweets(String query, int maxResults,
            String startTime, String endTime) {
        String token = resolveBearerToken();
        if (token == null) { log.warn("No Bearer Token — skipping search"); return List.of(); }
        try {
            var headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            var builder = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                .queryParam("query", query)
                .queryParam("max_results", maxResults)
                .queryParam("tweet.fields", "public_metrics,author_id");
            if (startTime != null && !startTime.isBlank())
                builder.queryParam("start_time", startTime);
            if (endTime != null && !endTime.isBlank())
                builder.queryParam("end_time", endTime);
            String url = builder.build().toString();

            var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).get("data");
            List<Map<String, String>> results = new ArrayList<>();
            if (data != null && data.isArray()) {
                for (JsonNode t : data) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", t.get("id").asText());
                    entry.put("text", t.get("text").asText());
                    entry.put("author_id", t.get("author_id").asText(""));
                    JsonNode m = t.get("public_metrics");
                    if (m != null) {
                        int eng = m.get("like_count").asInt(0) + m.get("retweet_count").asInt(0)
                                + m.get("reply_count").asInt(0) + m.get("quote_count").asInt(0);
                        entry.put("engagement", String.valueOf(eng));
                    }
                    results.add(entry);
                }
            }
            log.info("Found {} tweets for query ({} — {})", results.size(), startTime, endTime);
            return results;
        } catch (Exception e) { log.error("Search failed", e); return List.of(); }
    }

    public String getMyUserId() {
        if (accessToken != null && accessToken.contains("-"))
            return accessToken.substring(0, accessToken.indexOf('-'));
        return null;
    }

    // ---- OAuth 2.0 Bearer Token ----

    private String resolveBearerToken() {
        if (bearerToken != null && !bearerToken.isBlank()) return bearerToken;
        if (cachedBearerToken != null) return cachedBearerToken;
        cachedBearerToken = fetchBearerToken();
        return cachedBearerToken;
    }

    private String fetchBearerToken() {
        if (clientId == null || clientSecret == null || clientId.isBlank() || clientSecret.isBlank()) return null;
        try {
            String creds = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            var headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + creds);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            var resp = restTemplate.exchange(TOKEN_URL, HttpMethod.POST, new HttpEntity<>("grant_type=client_credentials", headers), String.class);
            String token = objectMapper.readTree(resp.getBody()).get("access_token").asText();
            log.info("Obtained Bearer Token via OAuth 2.0");
            return token;
        } catch (Exception e) { log.error("Failed to fetch Bearer Token", e); return null; }
    }

    // ---- OAuth 1.0a ----

    private String buildOAuthHeader() throws Exception {
        String nonce = generateNonce();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> oauthParams = new TreeMap<>();
        oauthParams.put("oauth_consumer_key", consumerKey);
        oauthParams.put("oauth_nonce", nonce);
        oauthParams.put("oauth_signature_method", OAUTH_SIGNATURE_METHOD);
        oauthParams.put("oauth_timestamp", timestamp);
        oauthParams.put("oauth_token", accessToken);
        oauthParams.put("oauth_version", OAUTH_VERSION);
        oauthParams.put("oauth_signature", generateSignature(oauthParams));

        StringBuilder header = new StringBuilder("OAuth ");
        boolean first = true;
        for (var e : oauthParams.entrySet()) {
            if (!first) header.append(", ");
            header.append(percentEncode(e.getKey())).append("=\"").append(percentEncode(e.getValue())).append("\"");
            first = false;
        }
        return header.toString();
    }

    private String generateSignature(Map<String, String> params) throws Exception {
        Map<String, String> all = new TreeMap<>(params);
        all.remove("oauth_signature");
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : all.entrySet()) {
            if (!first) sb.append("&");
            sb.append(percentEncode(e.getKey())).append("=").append(percentEncode(e.getValue()));
            first = false;
        }
        String base = "POST&" + percentEncode(TWEET_URL) + "&" + percentEncode(sb.toString());
        String key = percentEncode(consumerSecret) + "&" + percentEncode(accessTokenSecret);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
    }

    private String generateNonce() {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        StringBuilder sb = new StringBuilder();
        for (byte b : nonce) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private List<String> parseTweets(String summaryText) {
        List<String> tweets = new ArrayList<>();
        for (String line : summaryText.split("\n")) {
            String t = line.trim();
            if (t.startsWith("TWEET:")) tweets.add(t.substring(6).trim());
        }
        if (tweets.isEmpty()) {
            for (String para : summaryText.split("\n\n")) {
                String t = para.trim();
                if (!t.isEmpty()) tweets.add(t);
            }
        }
        if (tweets.isEmpty()) tweets.add(summaryText.trim());
        return tweets;
    }
}
