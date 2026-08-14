// market.stock.* 프로퍼티(MarketStockProperties)를 빈으로 등록하는 market 도메인 설정 클래스.
package com.finplay.api.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// record 기반 @ConfigurationProperties는 명시적으로 등록해야 빈이 된다 (MarketCryptoConfig와 같은 방식).
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketStockProperties.class)
public class MarketStockConfig {}
