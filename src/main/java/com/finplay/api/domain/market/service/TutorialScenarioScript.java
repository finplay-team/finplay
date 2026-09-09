package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public record TutorialScenarioScript(
	short version,
	Market market,
	BigDecimal basePrice,
	List<TutorialScenarioStage> stages,
	List<TutorialScenarioEvent> events) {

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

	public TutorialScenarioStage firstStage() {
		if (stages.isEmpty()) {
			throw new IllegalStateException("구간이 없는 대본입니다.");
		}
		return stages.get(0);
	}

	public Optional<TutorialScenarioStage> nextStage(String stageId) {
		int index = indexOf(stageId);
		return index == stages.size() - 1 ? Optional.empty() : Optional.of(stages.get(index + 1));
	}

	public Optional<TutorialScenarioStage> nextProgressStage(String stageId) {
		for (int index = indexOf(stageId) + 1; index < stages.size(); index++) {
			if (stages.get(index).kind() == TutorialScenarioStageKind.PROGRESS) {
				return Optional.of(stages.get(index));
			}
		}
		return Optional.empty();
	}

	private int indexOf(String stageId) {
		for (int index = 0; index < stages.size(); index++) {
			if (stages.get(index).id().equals(stageId)) {
				return index;
			}
		}
		throw new IllegalArgumentException("대본에 없는 구간입니다: " + stageId);
	}
}
