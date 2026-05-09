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
    private static final String OAUTH_SIGNATURE_METHOD = "HMAC-SHA1";
    private static final String OAUTH_VERSION = "1.0";
    private static final String TWITTER_API_URL = "https://api.twitter.com/2/tweets";

    private final String consumerKey;
    private final String consumerSecret;
    private final String accessToken;
    private final String accessTokenSecret;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public TwitterPostService(TwitterConfig config, ObjectMapper objectMapper) {
        this.consumerKey = config.getConsumerKey();
        this.consumerSecret = config.getConsumerSecret();
        this.accessToken = config.getAccessToken();
        this.accessTokenSecret = config.getAccessTokenSecret();
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
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
        String nonce = generateNonce();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        Map<String, String> oauthParams = new TreeMap<>();
        oauthParams.put("oauth_consumer_key", consumerKey);
        oauthParams.put("oauth_nonce", nonce);
        oauthParams.put("oauth_signature_method", OAUTH_SIGNATURE_METHOD);
        oauthParams.put("oauth_timestamp", timestamp);
        oauthParams.put("oauth_token", accessToken);
        oauthParams.put("oauth_version", OAUTH_VERSION);

        String signature = generateSignature(oauthParams);
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

    private String generateSignature(Map<String, String> params) throws Exception {
        // Collect all params including oauth params
        Map<String, String> allParams = new TreeMap<>(params);
        allParams.remove("oauth_signature");

        // Build parameter string
        StringBuilder paramString = new StringBuilder();
        boolean first = true;
        for (var entry : allParams.entrySet()) {
            if (!first) paramString.append("&");
            paramString.append(percentEncode(entry.getKey()))
                       .append("=")
                       .append(percentEncode(entry.getValue()));
            first = false;
        }

        String signatureBase = "POST&"
            + percentEncode(TWITTER_API_URL) + "&"
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
