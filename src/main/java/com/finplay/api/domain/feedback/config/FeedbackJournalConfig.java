package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeedbackJournalProperties.class)
public class FeedbackJournalConfig {}
