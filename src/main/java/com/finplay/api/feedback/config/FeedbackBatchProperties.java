// feedback.batch.* 설정값(개장 전 배치 크론)을 바인딩하는 프로퍼티 record — FeedbackBatchService가 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 docs/specs/012-ai-feedback/spec.md §C-1이다. FeedbackNewsProperties와 같은 방침이며
// (§C-7 — yml과 @DefaultValue 양쪽에 값을 두고 드리프트 테스트로 대조), 실제로 스케줄을 결정하는 것은
// @Scheduled가 읽는 application.yml 쪽이고 여기 @DefaultValue는 바닥값이다.
//
// zone은 여기 없다. cron 기반 @Scheduled에 zone = "Asia/Seoul"을 붙이는 것은 스케줄 선언부의 책임이다(§C-1).
@ConfigurationProperties(prefix = "feedback.batch")
public record FeedbackBatchProperties(
	// 개장 전 배치 (주식 카드·요약·브리핑). 재생세션 확정 배치(08:40)보다 5분 뒤다 — 같은 시각 두 크론은
	// 실행 순서가 보장되지 않아 세션이 아직 PREPARING인 채로 배치가 돌면 매일 조용히 0건이 된다(FEED-004).
	@DefaultValue("0 45 8 * * MON-FRI")
	String cron,
	// 코인 요약·브리핑 갱신 (매시 05분). 코인은 24시간 거래라 '개장 전에 미리 만들어 둘 시점'이 없어
	// 주기 갱신으로 대신한다 (FEED-008·FEED-009). 정각이 아니라 05분인 것은 매시 정각에 몰리는 다른
	// 작업과 겹치지 않게 하려는 것이며 §C-1의 확정값이다.
	@DefaultValue("0 5 * * * *")
	String cryptoCron,
	// 장 마감 집단 비교 확정 집계 (FEED-010·011, 이슈 #212). 15:30 장 마감보다 뒤인 15:32에 둬 그 시각의
	// 마지막 분봉·체결이 반영될 여유를 준다.
	@DefaultValue("0 32 15 * * MON-FRI")
	String peerStatsCron,
	// 코인 변동 감시 (CryptoPriceMoveWatcher, 이슈 #225). market.crypto.price-snapshot-cron(매 분 정각)과
	// 초를 30초 어긋내 — 같은 시각이면 실행 순서가 보장되지 않아 감시가 그 분의 스냅샷을 못 볼 수 있다(§C-1).
	@DefaultValue("30 * * * * *")
	String cryptoWatchCron,
	// 코인 집단 비교 확정 집계 (§FEED-012 결정 3, 이슈 #275). 매일 00:05에 돌아 전날 KST 하루치 코인 카드를
	// 집계한다 — 코인은 장 마감이 없어 '하루가 끝나는 시점'이 자정이고, 00:00 정각이 아니라 05분인 것은
	// 그 직전 자정 경계 체결·분봉이 반영될 여유를 주기 위해서다(§C-1).
	@DefaultValue("0 5 0 * * *")
	String cryptoPeerStatsCron) {
}
