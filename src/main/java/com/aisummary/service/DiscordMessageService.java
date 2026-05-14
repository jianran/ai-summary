package com.aisummary.service;

import com.aisummary.config.DiscordConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class DiscordMessageService {

    private static final Logger log = LoggerFactory.getLogger(DiscordMessageService.class);
    private static final int DISCORD_MAX_LENGTH = 2000;
    private static final String DISCORD_API = "https://discord.com/api/v10";

    private final String botToken;
    private final String targetUserId;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DiscordMessageService(DiscordConfig config, ObjectMapper objectMapper) {
        this.botToken = config.getBotToken();
        this.targetUserId = config.getTargetUserId();
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    public void sendRoundup(String content) {
        if (content == null || content.isBlank()) {
            log.warn("Empty roundup content, skipping Discord send");
            return;
        }

        if (botToken == null || botToken.isBlank()) {
            log.warn("No Discord bot token configured — skipping roundup");
            return;
        }

        if (targetUserId == null || targetUserId.isBlank()) {
            log.warn("No target user ID configured — skipping roundup");
            return;
        }

        try {
            String channelId = createDmChannel(botToken, targetUserId);
            List<String> chunks = splitMessage(content, DISCORD_MAX_LENGTH);
            for (int i = 0; i < chunks.size(); i++) {
                String text = i == 0 ? chunks.get(i) : "(cont...) " + chunks.get(i);
                sendMessage(botToken, channelId, text);
                log.info("Sent Discord roundup chunk ({}/{}): {}", i + 1, chunks.size(), text.substring(0, Math.min(60, text.length())));
                Thread.sleep(500);
            }
            log.info("Sent {} Discord roundup messages to user {}", chunks.size(), targetUserId);
        } catch (Exception e) {
            log.error("Failed to send Discord roundup", e);
        }
    }

    private String createDmChannel(String token, String userId) throws Exception {
        var headers = authHeaders(token);
        var body = new HttpEntity<>("{}", headers);

        var response = restTemplate.exchange(
            DISCORD_API + "/users/@me/channels",
            HttpMethod.POST,
            body,
            String.class
        );

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.get("id").asText();
    }

    private void sendMessage(String token, String channelId, String content) throws Exception {
        var headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        var bodyObj = new java.util.LinkedHashMap<String, Object>();
        bodyObj.put("content", content);
        String bodyJson = objectMapper.writeValueAsString(bodyObj);
        var body = new HttpEntity<>(bodyJson, headers);

        restTemplate.exchange(
            DISCORD_API + "/channels/" + channelId + "/messages",
            HttpMethod.POST,
            body,
            String.class
        );
    }

    private HttpHeaders authHeaders(String token) {
        var headers = new HttpHeaders();
        headers.set("Authorization", "Bot " + token);
        return headers;
    }

    private List<String> splitMessage(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLen, text.length());
            if (end < text.length()) {
                int newline = text.indexOf('\n', start);
                int space = text.lastIndexOf(' ', end);
                if (space > start + maxLen / 2) {
                    end = space;
                } else if (newline > start && newline < end) {
                    end = newline;
                }
            }
            chunks.add(text.substring(start, end).trim());
            start = end;
        }
        return chunks;
    }
}
