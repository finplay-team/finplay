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
	@DefaultValue("512")
	int maxTokens,
	// 요약·브리핑이 후검증에 걸렸을 때 재생성하는 횟수. 카드·매도 회고는 템플릿이 있어 재생성하지 않는다.
	@DefaultValue("1")
	int maxRegeneration,
	// 매도 회고 서술 재생성의 체결 1건당 누적 재시도 상한. 날짜 단위로 리셋하지 않는다.
	@DefaultValue("3")
	int maxNarrativeRetry) {
}
