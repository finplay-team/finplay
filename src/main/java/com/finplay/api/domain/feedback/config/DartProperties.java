package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dart")
public record DartProperties(String apiKey) {
}
