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
	String disclosureCron,
	// 주식 장중 카드 근거창의 이전 방향 폭(분). 근거창은 [windowEnd - 이 값, windowEnd + match-after-minutes]다(§C-2).
	@DefaultValue("30")
	int matchBeforeMinutes,
	// 같은 근거창의 이후 방향 폭(분). 기사가 가격 움직임보다 늦게 뜨는 경우가 있어 뒤쪽도 연다.
	@DefaultValue("5")
	int matchAfterMinutes,
	// 카드 1건에 붙일 최대 근거 수. 넘으면 발행시각이 이벤트에 가까운 순으로 자른다(§뉴스 매칭 범위).
	@DefaultValue("5")
	int maxSourcesPerCard,
	// Part C(종목 뉴스 목록) 응답의 items 상한. 넘으면 발행시각 내림차순으로 자른다(§뉴스 매칭 범위).
	@DefaultValue("50")
	int maxItemsPerNewsList,
	// Part D(개장 전 브리핑) 응답의 items 상한. 시장 전체가 대상이라 Part C와 값이 다르다.
	@DefaultValue("30")
	int maxItemsPerBriefing,
	// 요약·브리핑 LLM 입력 기사 수 상한. 응답 상한과 별개이며 자르는 것은 호출부 책임이다(§C-7).
	@DefaultValue("30")
	int maxItemsPerSummary) {

	// 여기 있는 것만 막는다 — 잘못된 값이 예외도 로그도 없이 "근거 0건 → 카드 미생성"이나 "목록이 통째로 빔"으로
	// 나타나기 때문이다(FEED-003·FEED-008). 크론은 문자열이라 여기서 검증하지 않는다(파싱 가능성은
	// NewsCollectionPropertiesTest가 확정한다).
	public FeedbackNewsProperties {
		if (matchBeforeMinutes < 0 || matchAfterMinutes < 0) {
			// 음수면 근거창의 시작이 끝보다 늦어 BETWEEN이 항상 빈 결과다 — 카드가 매일 0건이 된다.
			throw new IllegalArgumentException("feedback.news의 근거창 폭은 0 이상이어야 합니다.");
		}
		if (maxSourcesPerCard < 1) {
			// 0이면 근거를 전부 잘라 내고, 근거 0건인 카드는 만들지 않으므로 카드가 하나도 생기지 않는다.
			throw new IllegalArgumentException("feedback.news.max-sources-per-card는 1 이상이어야 합니다.");
		}
		// 목록 상한 3종도 같은 이유로 막는다. 0이면 응답의 items가 통째로 비고 요약 프롬프트에 기사가 하나도
		// 실리지 않는데, 상태값은 그대로 READY라 예외도 로그도 남지 않고 화면만 빈다.
		if (maxItemsPerNewsList < 1) {
			throw new IllegalArgumentException("feedback.news.max-items-per-news-list는 1 이상이어야 합니다.");
		}
		if (maxItemsPerBriefing < 1) {
			throw new IllegalArgumentException("feedback.news.max-items-per-briefing은 1 이상이어야 합니다.");
		}
		if (maxItemsPerSummary < 1) {
			throw new IllegalArgumentException("feedback.news.max-items-per-summary는 1 이상이어야 합니다.");
		}
	}
}
