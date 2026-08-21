// 대본의 한 구간과 그 구간의 분 단위 기준가 배율을 담는 값 객체
package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.util.List;

public record TutorialScenarioStage(
	String id, String act, TutorialScenarioStageKind kind, int minutes, List<BigDecimal> ratios) {

	public TutorialScenarioStage {
		ratios = ratios == null ? List.of() : List.copyOf(ratios);
	}
}
