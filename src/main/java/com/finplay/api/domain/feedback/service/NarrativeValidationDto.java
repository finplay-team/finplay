// 후검증 결과 — 적발된 금지 표현 목록을 담는다. 비어 있으면 통과다.
package com.finplay.api.domain.feedback.service;

import java.util.List;

/**
 * boolean이 아니라 목록인 이유가 있다. 요약·브리핑의 재생성 프롬프트가 <b>걸린 표현 자체</b>를 그대로
 * 되돌려 줘야 하기 때문이다 (spec §후검증의 확정 문구). 무엇에 걸렸는지 알려주지 않으면 2차 시도도 같은
 * 단어를 쓸 확률이 높고, 요약에는 템플릿이 없어 곧바로 {@code NONE}이 된다.
 *
 * <p>{@code detectedExpressions}는 §후검증 표의 줄 순서·표현 순서를 그대로 따르므로 같은 입력에 항상
 * 같은 순서가 나온다 — 재생성 프롬프트가 재현 가능해야 한다.
 */
public record NarrativeValidationDto(List<String> detectedExpressions) {

	public NarrativeValidationDto {
		detectedExpressions = List.copyOf(detectedExpressions);
	}

	public boolean passed() {
		return this.detectedExpressions.isEmpty();
	}
}
