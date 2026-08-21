// 시장 하나의 저작 대본 전체(구간 목록과 사건 목록)를 담는 불변 값 객체
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

// basePrice는 대본마다 다르므로 생성기 상수가 아니라 파일 필드다(049 ORDERBASICS-003). 배율 1.000000이
// 곧 이 값이며, 진행 중 봉과 과거 29봉이 **같은 기준가**를 써야 차트가 한 화면에 들어온다.
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

	// 구간을 다 쓰면 목록 순서대로 다음 구간으로 넘어간다. 마지막 구간이면 비어 있고 호출자가 FINISHED로
	// 판정한다(041 plan §상태 전이표 4행).
	public Optional<TutorialScenarioStage> nextStage(String stageId) {
		int index = indexOf(stageId);
		return index == stages.size() - 1 ? Optional.empty() : Optional.of(stages.get(index + 1));
	}

	// 대기 구간에서 매수하면 시간을 소비하지 않고 다음 진행 구간의 0분으로 점프한다(041 plan §상태 전이표
	// 2행). 대기 구간이 연달아 놓이는 대본도 허용하려고 다음 하나가 아니라 다음 PROGRESS를 찾는다.
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
