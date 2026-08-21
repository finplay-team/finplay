// 진행 조회가 예약 관련으로 내려보내는 세 값을 한 번에 담는 DTO (052 EXITFREE-020·021·022)
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeExitExperienceResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticePendingExitPlanResponse;

/**
 * 세 값이 <b>같은 조회 한 번</b>에서 나오게 묶는다 — 화면이 "지금 걸 수 있는가 / 이미 걸었는가 / 무엇을
 * 겪었는가"를 한 응답으로 그려야 하는데, 셋을 따로 계산하면 판정 사이에 체결이 끼어들어 서로 모순된 값이
 * 나갈 수 있다.
 *
 * @param creatable      지금 이 실행 세대에서 사용자 주도 예약을 만들 수 있는가.
 *                       {@code POST .../exit-plan}이 200을 줄 조건과 <b>같은 산출식</b>이다
 * @param pendingExitPlan 지금 걸려 있는 예약. 없으면 {@code null}
 * @param experience     이 실행 세대에서 손절·익절을 겪었는가. 판정할 것이 없으면 전부 false다
 */
public record PracticeExitPlanViewDto(
	boolean creatable,
	PracticePendingExitPlanResponse pendingExitPlan,
	PracticeExitExperienceResponse experience) {

	private static final PracticeExitPlanViewDto NONE = new PracticeExitPlanViewDto(false, null,
		PracticeExitExperienceResponse.none());

	/** 판정할 실행 세대가 없는 경로(attempt 없음·종목 미선택)의 값. */
	public static PracticeExitPlanViewDto none() {
		return NONE;
	}
}
