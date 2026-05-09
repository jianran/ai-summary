package com.aisummary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiSummaryApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSummaryApplication.class, args);
    }
}
