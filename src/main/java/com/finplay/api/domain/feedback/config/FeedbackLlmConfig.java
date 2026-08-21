// feedback.llm.* 프로퍼티(FeedbackLlmProperties)를 빈으로 등록하는 feedback 도메인 설정 클래스.
package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// record 기반 @ConfigurationProperties는 명시적으로 등록해야 빈이 된다 (market의 KisRestClientConfig와 같은 방식).
// @Bean 메서드가 없어 self-invocation이 없으므로 CGLIB 프록시(proxyBeanMethods)가 필요 없다.
//
// 여기에 다른 빈을 얹지 않는다. 프로퍼티 바인딩 테스트가 이 클래스 하나만 올리는 ApplicationContextRunner 슬라이스라,
// 외부 자동설정(Spring AI 등)에 의존하는 빈이 섞이면 컨텍스트가 기동조차 못 해 바인딩 단정이 통째로 무의미해진다.
// 특히 "숫자가 아닌 값이면 기동 실패" 케이스는 다른 이유로 실패해도 통과해 버려 보장이 조용히 사라진다 — 실제로
// ChatClient 빈을 여기 얹었다가 그 회귀를 냈다. ChatClient 조립은 NarrativeChatClientConfig에 있다.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeedbackLlmProperties.class)
public class FeedbackLlmConfig {}
