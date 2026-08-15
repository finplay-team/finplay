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
}
