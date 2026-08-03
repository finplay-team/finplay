// 서술 확정 결과 — 저장할 문장과 그 문장이 무엇으로 만들어졌는지를 함께 담는다.
package com.finplay.api.feedback.service;

/**
 * {@code source}가 {@code NONE}이면 {@code narrative}가 {@code null}이다 — 요약·브리핑에만 있는 상태이며
 * 재생성 1회 후에도 후검증에 걸렸거나 생성 호출 자체가 실패한 경우다 (spec §후검증).
 * 카드·매도 회고는 §템플릿 문장이 있어 항상 {@code LLM} 아니면 {@code TEMPLATE}이고 문장이 비지 않는다.
 *
 * <p>이 이슈가 단정하는 범위는 여기까지다. 상태값 {@code UNAVAILABLE} 매핑과 {@code items} 채움은 응답 DTO가
 * 필요해 #5 소유다 (plan.md §완료 조건 배정).
 */
public record NarrativeResultDto(String narrative, NarrativeSource source) {

	// 정규 생성자는 record만큼 공개적이어야 해서 숨길 수 없다. 정적 팩토리를 쓰는 규율만으로는 이 클래스를
	// 처음 보는 다음 이슈 구현자에게 불변식이 전달되지 않으므로 여기서 강제한다.
	//
	// 투기적 방어가 아니다 — #5가 `NONE` → `summary=NULL` → 조회 시 `UNAVAILABLE`(§C-4 판정 순서 5번) 매핑을
	// 이 짝 위에 얹는다. 어긋난 값이 들어오면 DB에 모순된 행이 남고, 조회 쪽에서는 원인이 보이지 않는다.
	public NarrativeResultDto {
		if (source == null) {
			throw new IllegalArgumentException("narrative_source는 null일 수 없습니다.");
		}
		if (source == NarrativeSource.NONE && narrative != null) {
			throw new IllegalArgumentException("NONE은 서술이 없는 상태입니다. narrative는 null이어야 합니다.");
		}
		if (source != NarrativeSource.NONE && (narrative == null || narrative.isBlank())) {
			throw new IllegalArgumentException("LLM·TEMPLATE은 서술이 있어야 합니다. narrative가 비어 있습니다.");
		}
	}

	public static NarrativeResultDto llm(String narrative) {
		return new NarrativeResultDto(narrative, NarrativeSource.LLM);
	}

	public static NarrativeResultDto template(String narrative) {
		return new NarrativeResultDto(narrative, NarrativeSource.TEMPLATE);
	}

	public static NarrativeResultDto none() {
		return new NarrativeResultDto(null, NarrativeSource.NONE);
	}

	public boolean hasNarrative() {
		return this.narrative != null;
	}
}
