package com.finplay.api.domain.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LimitOrderFillExecutorProperties.class)
public class LimitOrderFillExecutorConfig {}
