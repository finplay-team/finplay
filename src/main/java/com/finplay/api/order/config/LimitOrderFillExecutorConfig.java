// order.limit-fill-executor.* 프로퍼티(LimitOrderFillExecutorProperties)를 빈으로 등록하는 order 도메인 설정 클래스.
package com.finplay.api.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// record 기반 @ConfigurationProperties는 명시적으로 등록해야 빈이 된다 (feedback의 FeedbackQueryCacheConfig와
// 같은 방식). @Bean 메서드가 없어 self-invocation이 없으므로 CGLIB 프록시(proxyBeanMethods)가 필요 없다.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LimitOrderFillExecutorProperties.class)
public class LimitOrderFillExecutorConfig {}
