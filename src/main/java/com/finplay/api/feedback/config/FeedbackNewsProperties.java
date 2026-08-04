// feedback.news.* 설정값(뉴스·공시 상시 수집 크론)을 바인딩하는 프로퍼티 record — 수집 스케줄이 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 docs/specs/012-ai-feedback/spec.md §C-1이다. 크론을 코드 상수로 박지 않고 설정으로 두는 것은
// feedback.llm.*(FeedbackLlmProperties)과 같은 방침이며, §C-7이 "feedback.* 블록은 yml과 @DefaultValue
// 양쪽에 값을 둔다"로 정했다.
//
// 다만 @Scheduled(cron = "${feedback.news.collect-cron}")는 이 record가 아니라 Environment에서 값을 읽는다 —
// 여기 @DefaultValue는 동작값이 아니라 바닥값이고, 실제로 스케줄을 결정하는 것은 application.yml의 feedback.news
// 블록이다. 그래서 두 곳의 값이 갈리면 운영에서 크론만 조용히 바뀌므로 드리프트 테스트로 대조한다(§C-7).
//
// zone은 여기 없다. cron 기반 @Scheduled에 zone = "Asia/Seoul"을 붙이는 것은 스케줄 선언부의 책임이며(§C-1),
// 빠뜨리면 배포 JVM 기본 타임존이 UTC라 예외도 로그도 없이 엉뚱한 시각에 돈다.
@ConfigurationProperties(prefix = "feedback.news")
public record FeedbackNewsProperties(
	// 뉴스 수집. 장중이 아니라 종일 돈다 — 전장 구간(§C-2)이 약 17.5시간이라 장중만 돌리면 그 대부분이
	// 영영 수집되지 않는다(FEED-001).
	@DefaultValue("0 0/30 * * * *")
	String collectCron,
	// 공시 수집. 공시는 주식만이고 OpenDART 접수 시간대에만 새 건이 생긴다(FEED-001).
	@DefaultValue("0 0/30 8-20 * * MON-FRI")
	String disclosureCron) {
}
