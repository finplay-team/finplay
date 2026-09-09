package com.finplay.api.domain.ranking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RankingRebuildProperties.class)
public class RankingRebuildConfig {}
