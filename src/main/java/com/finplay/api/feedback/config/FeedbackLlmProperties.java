// feedback.llm.* 설정값(모델·타임아웃·최대 토큰·재생성 상한)을 바인딩하는 프로퍼티 record — LLM 서술 생성 경로가 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 docs/specs/012-ai-feedback/spec.md §C-7이다. 모델·타임아웃·최대 토큰을 코드 상수로 박지 않는 것이
// ADR-0011의 "프로바이더 교체는 starter 의존성과 feedback.llm.* 설정 변경으로 끝난다"는 결정이다.
// 설정을 하나도 주지 않아도 기동해야 하므로 전 항목에 @DefaultValue를 둔다.
@ConfigurationProperties(prefix = "feedback.llm")
public record FeedbackLlmProperties(
	@DefaultValue("gpt-5.4-mini")
	String model,
	// LLM 호출 1건의 타임아웃(초). 초과하면 실패로 취급해 템플릿으로 폴백한다.
	@DefaultValue("20")
	int timeoutSeconds,
	// 응답 1건의 최대 토큰. OpenAI 구현은 이 값을 OpenAiChatOptions.maxTokens가 아니라 maxCompletionTokens로 넘긴다 —
	// 둘은 별개 필드이고 각각 요청 본문의 max_tokens·max_completion_tokens로 그대로 나간다(Spring AI 2.0.0
	// OpenAiChatModel 바이트코드 확인). GPT-5 계열(기본 모델)은 max_tokens를 거부하므로 maxTokens로 넘기면
	// 전 호출이 실패해 서술이 통째로 템플릿이 된다. max_completion_tokens는 GPT-4.1 계열도 받으므로
	// §튜닝의 폴백 모델로 내려도 그대로 쓸 수 있다.
	//
	// 4차에 512에서 1024로 올렸다(§FEED-013 결정 5). 투자일기가 실리면 매도 회고가 6~8문장으로 길어지는데,
	// 토큰이 모자라 잘린 문장은 §후검증을 그대로 통과해 조용히 사용자에게 나간다. 기본 모델이 GPT-5 계열이라
	// 추론 토큰이 같은 예산을 나눠 쓸 수 있다는 것도 이유다(2026-08-03 실측 당시 0이었으나 프롬프트가 길어진
	// 뒤에도 0인지는 확인되지 않았다). 상한이라 짧은 파트(카드·요약·브리핑)의 비용은 늘지 않는다.
	@DefaultValue("1024")
	int maxTokens,
	// 요약·브리핑이 후검증에 걸렸을 때 재생성하는 횟수. 카드·매도 회고는 템플릿이 있어 재생성하지 않는다.
	@DefaultValue("1")
	int maxRegeneration,
	// 매도 회고 서술 재생성의 체결 1건당 누적 재시도 상한. 날짜 단위로 리셋하지 않는다.
	// 흐름·집단 사유(§C-5 게이트) 전용이며 아래 max-journal-regeneration과 따로 센다.
	@DefaultValue("3")
	int maxNarrativeRetry,
	// 투자일기 사유로 매도 회고 서술을 다시 만드는 체결 1건당 누적 상한 (§FEED-013 결정 3).
	// maxNarrativeRetry와 카운터를 합치지 않는 이유는, 합치면 일기를 여러 번 고친 체결이 그 상한을 먼저
	// 소진해 흐름·집단 반영 기회를 잃기 때문이다 — 예외도 로그도 없이 그렇게 된다.
	@DefaultValue("3")
	int maxJournalRegeneration) {

	// 음수를 막는 이유는 실패 모양이 조용하기 때문이다. maxRegeneration이 음수면 NarrativeService의 재생성
	// 루프가 0회 돌아 생성기를 한 번도 부르지 않고 NONE을 반환한다 — 예외도 로그도 없이 요약·브리핑이
	// 전부 사라진다. 0은 정상 동작이므로(재생성 없이 1회 생성) 하한만 본다.
	public FeedbackLlmProperties {
		if (maxRegeneration < 0) {
			throw new IllegalArgumentException("feedback.llm.max-regeneration은 0 이상이어야 합니다.");
		}
	}
}
