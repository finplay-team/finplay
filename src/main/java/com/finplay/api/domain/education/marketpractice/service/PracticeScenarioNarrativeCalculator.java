package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeScenarioEventResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCauseStatus;
import com.finplay.api.domain.market.service.TutorialScenarioEvent;
import com.finplay.api.domain.market.service.TutorialScenarioScript;
import com.finplay.api.domain.market.service.TutorialScenarioStage;
import com.finplay.api.domain.market.service.TutorialScenarioStageKind;
import java.util.ArrayList;
import java.util.List;

final class PracticeScenarioNarrativeCalculator {

	private static final String STAGE_FINISHED = "FINISHED";
	private static final int SECONDS_PER_VIRTUAL_MINUTE = PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE;

	private PracticeScenarioNarrativeCalculator() {}

	static PracticeScenarioNarrativeDto calculate(PracticeAttempt attempt, TutorialScenarioScript script) {
		boolean unstarted = attempt.getScenarioStageId() == null || attempt.getScenarioStageElapsedSeconds() == null;
		TutorialScenarioStage stage = unstarted
			? script.firstStage()
			: script.stage(attempt.getScenarioStageId());
		long elapsedSeconds = unstarted ? 0L : Math.max(0L, attempt.getScenarioStageElapsedSeconds());
		long minute = elapsedSeconds / SECONDS_PER_VIRTUAL_MINUTE;
		boolean finished = isLastStage(script, stage) && minute >= stage.minutes();

		List<TutorialScenarioEvent> revealed = revealedEvents(script, stage, minute);
		boolean revealedHere = revealed.stream().anyMatch(event -> event.stageId().equals(stage.id()));
		return new PracticeScenarioNarrativeDto(
			finished ? STAGE_FINISHED : stageLabel(stage),
			!finished && stage.kind() == TutorialScenarioStageKind.PROGRESS,
			(revealedHere ? PracticeCauseStatus.REVEALED : PracticeCauseStatus.NONE_KNOWN).name(),
			revealed.stream()
				.map(event -> new PracticeScenarioEventResponse(
					stageLabel(script.stage(event.stageId())), event.headline()))
				.toList());
	}

	private static List<TutorialScenarioEvent> revealedEvents(
		TutorialScenarioScript script, TutorialScenarioStage currentStage, long currentMinute) {
		int currentIndex = indexOf(script, currentStage.id());
		List<TutorialScenarioEvent> revealed = new ArrayList<>();
		for (TutorialScenarioEvent event : script.events()) {
			int eventIndex = indexOf(script, event.stageId());
			boolean open = eventIndex < currentIndex
				|| (eventIndex == currentIndex && event.revealMinute() <= currentMinute);
			if (open) {
				revealed.add(event);
			}
		}
		return revealed;
	}

	private static String stageLabel(TutorialScenarioStage stage) {
		return stage.act() == null ? stage.id() : stage.act();
	}

	private static boolean isLastStage(TutorialScenarioScript script, TutorialScenarioStage stage) {
		return indexOf(script, stage.id()) == script.stages().size() - 1;
	}

	private static int indexOf(TutorialScenarioScript script, String stageId) {
		List<TutorialScenarioStage> stages = script.stages();
		for (int index = 0; index < stages.size(); index++) {
			if (stages.get(index).id().equals(stageId)) {
				return index;
			}
		}
		throw new IllegalArgumentException("대본에 없는 구간입니다: " + stageId);
	}
}
