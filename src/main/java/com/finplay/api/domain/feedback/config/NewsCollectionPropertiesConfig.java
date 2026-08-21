// 뉴스·공시 수집 경로가 읽는 프로퍼티 record 3종을 빈으로 등록하는 feedback 도메인 설정 클래스.
package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// record 기반 @ConfigurationProperties는 명시적으로 등록해야 빈이 된다 (FeedbackLlmConfig·KisRestClientConfig와
// 같은 방식). 크론(feedback.news)과 자격증명(naver-search·dart)이 프리픽스는 달라도 쓰는 곳이 수집 경로 하나뿐이라
// 한 클래스에 모은다.
//
// FeedbackLlmConfig에 얹지 않은 이유는 그 클래스의 주석에 있다 — 그것 하나만 올리는 ApplicationContextRunner
// 슬라이스가 llm 블록의 바인딩을 단정하고 있어, 다른 프리픽스를 섞으면 그 슬라이스의 대상이 흐려진다.
//
// @Bean 메서드가 없어 self-invocation이 없으므로 CGLIB 프록시(proxyBeanMethods)가 필요 없다.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
	FeedbackNewsProperties.class,
	NaverSearchProperties.class,
	DartProperties.class
})
public class NewsCollectionPropertiesConfig {}
