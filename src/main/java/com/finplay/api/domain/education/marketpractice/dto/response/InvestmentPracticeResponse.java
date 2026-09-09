package com.finplay.api.domain.education.marketpractice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record InvestmentPracticeResponse(
	String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps,
	LocalDateTime completedAt, Long rewardAmount, PracticeAttemptResponse attempt,
	List<PracticeScenarioEventResponse> revealedEvents, BigDecimal priceAfterSell,
	List<PracticeEntryResponse> entries, PracticeStageProgressResponse tutorialStageProgress,
	ExitRateBoundsResponse exitRateBounds, boolean exitPlanCreatable,
	PracticePendingExitPlanResponse pendingExitPlan, PracticeExitExperienceResponse exitExperience) {

	public InvestmentPracticeResponse {
		steps = List.copyOf(steps);
		revealedEvents = revealedEvents == null ? List.of() : List.copyOf(revealedEvents);
		entries = entries == null ? List.of() : List.copyOf(entries);
		tutorialStageProgress = tutorialStageProgress == null
			? PracticeStageProgressResponse.none()
			: tutorialStageProgress;
		exitRateBounds = exitRateBounds == null ? ExitRateBoundsResponse.current() : exitRateBounds;
		exitExperience = exitExperience == null ? PracticeExitExperienceResponse.none() : exitExperience;
	}

	public InvestmentPracticeResponse(
		String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps,
		LocalDateTime completedAt, Long rewardAmount, PracticeAttemptResponse attempt,
		List<PracticeScenarioEventResponse> revealedEvents, BigDecimal priceAfterSell,
		List<PracticeEntryResponse> entries, PracticeStageProgressResponse tutorialStageProgress) {
		this(
			tutorialKey, status, currentStep, steps, completedAt, rewardAmount, attempt, revealedEvents,
			priceAfterSell, entries, tutorialStageProgress, ExitRateBoundsResponse.current(), false, null,
			PracticeExitExperienceResponse.none());
	}

	public InvestmentPracticeResponse(
		String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps,
		LocalDateTime completedAt, Long rewardAmount, PracticeAttemptResponse attempt,
		List<PracticeScenarioEventResponse> revealedEvents, BigDecimal priceAfterSell,
		List<PracticeEntryResponse> entries, PracticeStageProgressResponse tutorialStageProgress,
		boolean exitPlanCreatable, PracticePendingExitPlanResponse pendingExitPlan,
		PracticeExitExperienceResponse exitExperience) {
		this(
			tutorialKey, status, currentStep, steps, completedAt, rewardAmount, attempt, revealedEvents,
			priceAfterSell, entries, tutorialStageProgress, ExitRateBoundsResponse.current(), exitPlanCreatable,
			pendingExitPlan, exitExperience);
	}

	public InvestmentPracticeResponse(
		String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps,
		LocalDateTime completedAt, Long rewardAmount, PracticeAttemptResponse attempt) {
		this(
			tutorialKey, status, currentStep, steps, completedAt, rewardAmount, attempt, List.of(), null, List.of(),
			PracticeStageProgressResponse.none(), ExitRateBoundsResponse.current(), false, null,
			PracticeExitExperienceResponse.none());
	}
}
