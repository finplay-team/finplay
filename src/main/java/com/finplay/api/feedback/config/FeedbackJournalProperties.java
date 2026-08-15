// feedback.journal.* 설정값(프롬프트에 실을 투자일기 건수·본문 절단 길이)을 바인딩하는 프로퍼티 record — 매도 회고 서술 조립이 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 docs/specs/012-ai-feedback/spec.md §C-7이다(4차 신설, §FEED-013 결정 4).
//
// yml과 @DefaultValue 양쪽에 값을 두는 것은 §C-7의 확정 방침이다(feedback.llm·feedback.detection이 선례다) —
// 설정 없이도 기동하는 것을 실제로 보장하는 것은 record의 @DefaultValue이고, 운영 중 값을 바꿀 때는
// 항상 이기는 yml만 고친다. 두 곳이 갈리면 드리프트 테스트가 잡는다.
@ConfigurationProperties(prefix = "feedback.journal")
public record FeedbackJournalProperties(
	// 프롬프트에 넣을 매수 회고 수 상한. 매수 시각 오름차순으로 이만큼만 싣는다.
	@DefaultValue("3")
	int maxBuyJournals,
	// 일기 1건의 본문 절단 길이. 원본 상한이 5000자라 절단이 없으면 프롬프트가 기사 목록보다 커진다.
	@DefaultValue("500")
	int maxJournalChars) {

	// FeedbackNewsProperties의 max-items-* 검증과 같은 처방이다 — 잘못된 값이 예외도 로그도 없이
	// "상한이 통째로 무효" 또는 "매도 회고 조회가 500"으로만 나타나기 때문에 기동에서 막는다.
	public FeedbackJournalProperties {
		if (maxBuyJournals < 1) {
			// 0 이하이면 선별 루프의 상한 비교가 걸리지 않아 배분된 매수 일기가 전부 프롬프트에 실린다 —
			// 기능을 끄려고 0을 넣은 운영자가 정반대 결과를 받는다.
			throw new IllegalArgumentException("feedback.journal.max-buy-journals는 1 이상이어야 합니다.");
		}
		if (maxJournalChars < 1) {
			// 음수면 절단이 substring(0, -N)으로 StringIndexOutOfBoundsException을 던져
			// "매도 회고 조회는 항상 200·READY"(§C-4)가 500으로 깨진다.
			throw new IllegalArgumentException("feedback.journal.max-journal-chars는 1 이상이어야 합니다.");
		}
	}
}
