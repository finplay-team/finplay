// 완성된 프롬프트 문자열만 받아 LLM 서술을 생성하는 추상화 — 프로바이더 교체 지점이다 (ADR-0011).
package com.finplay.api.feedback.service;

import java.util.Optional;

// 프롬프트 조립(NarrativePromptBuilder)·후검증(NarrativeValidator)·재시도(NarrativeService)는 이 인터페이스 밖에 있다.
// 구현은 어떤 파트의 서술인지 모르며 문자열 두 개만 본다 (spec §C-6).
public interface NarrativeGenerator {

	/**
	 * 실패를 예외로 던지지 않는다 — 키 없음·타임아웃·HTTP 오류·빈 응답이 전부 `Optional.empty()`로 수렴한다.
	 * 호출부가 그 하나만 보고 템플릿으로 폴백할 수 있어야 한다 (ADR-0011: LLM 실패는 정상 경로다).
	 */
	Optional<String> generate(String systemPrompt, String userPrompt);
}
