package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "naver-search")
public record NaverSearchProperties(String clientId, String clientSecret) {
}
