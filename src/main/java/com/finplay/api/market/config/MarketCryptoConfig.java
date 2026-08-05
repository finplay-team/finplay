// market.crypto.* 프로퍼티(MarketCryptoProperties)를 빈으로 등록하는 market 도메인 설정 클래스.
package com.finplay.api.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// record 기반 @ConfigurationProperties는 명시적으로 등록해야 빈이 된다 (feedback의 FeedbackBatchConfig와 같은 방식).
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketCryptoProperties.class)
public class MarketCryptoConfig {}
