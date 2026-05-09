package com.aisummary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "twitter")
public class TwitterConfig {

    private String consumerKey;
    private String consumerSecret;
    private String accessToken;
    private String accessTokenSecret;
    private String clientId;
    private String clientSecret;
    private String bearerToken;

    public String getConsumerKey() { return consumerKey; }
    public void setConsumerKey(String consumerKey) { this.consumerKey = consumerKey; }

    public String getConsumerSecret() { return consumerSecret; }
    public void setConsumerSecret(String consumerSecret) { this.consumerSecret = consumerSecret; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getAccessTokenSecret() { return accessTokenSecret; }
    public void setAccessTokenSecret(String accessTokenSecret) { this.accessTokenSecret = accessTokenSecret; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getBearerToken() { return bearerToken; }
    public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
}
