package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeScenarioEventResponse;
import java.util.List;

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
