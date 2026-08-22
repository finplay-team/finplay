// 대본 커서에서 파생한 진행 상태(막·진행 여부·원인 상태·공개된 사건)를 묶는 조회 전용 DTO
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeScenarioEventResponse;
import java.util.List;

/**
 * 041 6번. 어떤 것도 저장하지 않고 {@code practice_attempts}의 대본 커서와 대본 파일만 읽어 만든다.
 *
 * @param scenarioStage       {@code IDLE_ENTRY|ACT1|ACT2|IDLE_REENTRY|ACT3|ACT4|FINISHED}. 대본 내부
 *                            구간이 아니라 <b>act 단위</b>다 — 2막이 셋으로 쪼개진 것은 대본의 사정이고
 *                            사용자에게는 한 막이다. 대본을 쓰지 않는 attempt는 {@code null}
 * @param scenarioProgressing 현재 구간이 진행 구간인가. {@code false}면 대기 구간이며 <b>보유 여부와
 *                            무관하다</b> — 4막을 관전 중인 미보유 사용자도 {@code true}다. 대본이 끝나면
 *                            더 진행할 것이 없으므로 {@code false}다. 대본을 쓰지 않는 attempt는 {@code null}
 * @param causeStatus         {@code REVEALED|NONE_KNOWN}. 대본을 쓰지 않는 attempt는 {@code null}
 * @param revealedEvents      공개 시점이 지난 사건만, 공개 순서(오래된 것 → 최근)다
 */
record PracticeScenarioNarrativeDto(
	String scenarioStage,
	Boolean scenarioProgressing,
	String causeStatus,
	List<PracticeScenarioEventResponse> revealedEvents) {

	static final PracticeScenarioNarrativeDto EMPTY = new PracticeScenarioNarrativeDto(null, null, null, List.of());

	PracticeScenarioNarrativeDto {
		revealedEvents = revealedEvents == null ? List.of() : List.copyOf(revealedEvents);
	}
}
