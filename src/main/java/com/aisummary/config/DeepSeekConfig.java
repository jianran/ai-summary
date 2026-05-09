package com.aisummary.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DeepSeekConfig {

    @Value("${deepseek.api-key}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    @Bean
    public OpenAiApi deepseekApi() {
        return OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build();
    }

    @Bean
    public OpenAiChatModel deepseekChatModel(OpenAiApi deepseekApi) {
        return OpenAiChatModel.builder()
            .openAiApi(deepseekApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.7)
                .build())
            .build();
    }
}
