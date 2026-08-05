// feedback.crypto.* 프로퍼티(FeedbackCryptoProperties)를 빈으로 등록하는 feedback 도메인 설정 클래스.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// record 기반 @ConfigurationProperties는 명시적으로 등록해야 빈이 된다 (FeedbackDetectionConfig·FeedbackLlmConfig와
// 같은 방식). @Bean 메서드가 없어 self-invocation이 없으므로 CGLIB 프록시(proxyBeanMethods)가 필요 없다.
//
// NewsCollectionPropertiesConfig·FeedbackDetectionConfig에 얹지 않고 프리픽스마다 설정 클래스를 나눈 것도
// 같은 이유다 — 바인딩 테스트가 이 클래스 하나만 올리는 ApplicationContextRunner 슬라이스라, 다른 프리픽스나
// 외부 자동설정에 의존하는 빈이 섞이면 "잘못된 값이면 기동 실패" 단정이 다른 이유로도 통과해 버린다.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeedbackCryptoProperties.class)
public class FeedbackCryptoConfig {}
