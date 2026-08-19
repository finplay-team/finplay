// 시장 하나의 저작 대본 전체(구간 목록과 사건 목록)를 담는 불변 값 객체
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Market;
import java.util.List;

public record TutorialScenarioScript(
	short version, Market market, List<TutorialScenarioStage> stages, List<TutorialScenarioEvent> events) {

	public TutorialScenarioScript {
		stages = stages == null ? List.of() : List.copyOf(stages);
		events = events == null ? List.of() : List.copyOf(events);
	}

	public TutorialScenarioStage stage(String stageId) {
		return stages.stream()
			.filter(stage -> stage.id().equals(stageId))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("대본에 없는 구간입니다: " + stageId));
	}
}
