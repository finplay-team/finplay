package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.util.List;

public record TutorialScenarioStage(
	String id, String act, TutorialScenarioStageKind kind, int minutes, List<BigDecimal> ratios) {

	public TutorialScenarioStage {
		ratios = ratios == null ? List.of() : List.copyOf(ratios);
	}
}
