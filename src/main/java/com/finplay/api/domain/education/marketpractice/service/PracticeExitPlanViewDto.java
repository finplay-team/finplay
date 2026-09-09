package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeExitExperienceResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticePendingExitPlanResponse;

public record PracticeExitPlanViewDto(
	boolean creatable,
	PracticePendingExitPlanResponse pendingExitPlan,
	PracticeExitExperienceResponse experience) {

	private static final PracticeExitPlanViewDto NONE = new PracticeExitPlanViewDto(false, null,
		PracticeExitExperienceResponse.none());

	public static PracticeExitPlanViewDto none() {
		return NONE;
	}
}
