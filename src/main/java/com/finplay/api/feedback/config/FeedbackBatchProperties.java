// feedback.batch.* 설정값(개장 전 배치 크론)을 바인딩하는 프로퍼티 record — FeedbackBatchService가 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 docs/specs/012-ai-feedback/spec.md §C-1이다. FeedbackNewsProperties와 같은 방침이며
// (§C-7 — yml과 @DefaultValue 양쪽에 값을 두고 드리프트 테스트로 대조), 실제로 스케줄을 결정하는 것은
// @Scheduled가 읽는 application.yml 쪽이고 여기 @DefaultValue는 바닥값이다.
//
// §C-1의 나머지 크론 3종(peer-stats·crypto·crypto-watch)은 그 스케줄을 실제로 더하는 이슈가 함께 추가한다 —
// 지금 넣어 두면 아무 데서도 읽지 않는 키가 되고, 풀 크기 계산("풀 크기 = 등록된 @Scheduled 수")도 흐려진다.
//
// zone은 여기 없다. cron 기반 @Scheduled에 zone = "Asia/Seoul"을 붙이는 것은 스케줄 선언부의 책임이다(§C-1).
@ConfigurationProperties(prefix = "feedback.batch")
public record FeedbackBatchProperties(
	// 개장 전 배치 (주식 카드·요약·브리핑). 재생세션 확정 배치(08:40)보다 5분 뒤다 — 같은 시각 두 크론은
	// 실행 순서가 보장되지 않아 세션이 아직 PREPARING인 채로 배치가 돌면 매일 조용히 0건이 된다(FEED-004).
	@DefaultValue("0 45 8 * * MON-FRI")
	String cron) {
}
