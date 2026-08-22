// 대본 커서를 읽어 노출 가능한 진행 상태와 공개된 사건만 골라내는 순수 계산기(어떤 것도 저장하지 않는다)
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

/**
 * 041 SCENARIO-015·016. <b>노출 게이트가 여기 하나뿐이다</b> — tick·차트 조회와 진행 조회가 같은 계산을
 * 쓰므로, 한 창구만 막고 다른 창구로 새는 조합이 생기지 않는다.
 *
 * <p><b>공개 전 사건은 어떤 형태로도 나가지 않는다.</b> 문안·시각은 물론 개수·자리표시자도 만들지 않으며,
 * {@link PracticeCauseStatus}가 두 값뿐인 것도 같은 이유다.
 */
final class PracticeScenarioNarrativeCalculator {

	private static final String STAGE_FINISHED = "FINISHED";
	private static final int SECONDS_PER_VIRTUAL_MINUTE = PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE;

	private PracticeScenarioNarrativeCalculator() {}

	/**
	 * @param script {@code attempt.usesScenarioScript()}가 참일 때 그 attempt의 시장 대본. 대본을 쓰지
	 *     않는 attempt(생성기 버전 1·완료 replay)는 호출부가 {@link PracticeScenarioNarrativeDto#EMPTY}를
	 *     쓴다 — 네 필드가 전부 비어 나가며 클라이언트는 대본 UI를 그리지 않는다
	 */
	static PracticeScenarioNarrativeDto calculate(PracticeAttempt attempt, TutorialScenarioScript script) {
		// 커서가 비어 있으면 미시작이다 — 종목 선택·재시작이 다섯 컬럼을 전부 null로 지우고 첫 tick이
		// 대본의 첫 구간 0분으로 세운다(041 3·4번이 정한 계약). **두 컬럼 중 하나만 null이어도 미시작으로
		// 읽는다** — 가격을 정하는 PracticeAttemptCanonicalPriceService.cursor와 같은 판정이라야
		// "막은 3막인데 가격은 0막"처럼 두 값이 갈라지지 않는다(둘을 짝으로 묶는 DB CHECK는 없다).
		boolean unstarted = attempt.getScenarioStageId() == null || attempt.getScenarioStageElapsedSeconds() == null;
		TutorialScenarioStage stage = unstarted
			? script.firstStage()
			: script.stage(attempt.getScenarioStageId());
		long elapsedSeconds = unstarted ? 0L : Math.max(0L, attempt.getScenarioStageElapsedSeconds());
		// clamp하지 않은 분을 공개 판정에 쓴다. 마지막 구간을 다 쓰면 커서가 구간 길이에 닿은 채 멈추는데
		// (FINISHED), 가격용으로 마지막 분에 clamp한 값을 여기 쓰면 그 구간의 사건 하나가 영영 열리지 않는다.
		long minute = elapsedSeconds / SECONDS_PER_VIRTUAL_MINUTE;
		boolean finished = isLastStage(script, stage) && minute >= stage.minutes();

		List<TutorialScenarioEvent> revealed = revealedEvents(script, stage, minute);
		// **막이 아니라 대본 구간으로 판정한다.** 2막은 세 구간(루머·속임수 반등·확정)이 같은 act 라벨을
		// 쓰는데, 막으로 보면 루머의 원인이 열린 뒤 속임수 반등 구간에서도 REVEALED가 되어 "원인 없는
		// 변동"이라는 그 구간의 설계 의도(SCENARIO-005·016, plan §사건 배치)가 사라진다.
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

	// 대본 구간 순서가 곧 공개 순서다 — 지나온 구간의 사건은 전부 열려 있고, 지금 구간은 공개 분에 닿은
	// 것만 열린다. 대기 구간은 되감기지만 그 구간에는 사건이 없고(로더가 강제하지 않으므로 아래 비교가
	// 구간 인덱스를 함께 보는 것으로 충분하다) 구간 인덱스 자체는 되돌아가지 않는다.
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

	// act가 있으면 act, 없으면(대기 구간) 구간 id다. 대본이 2막을 셋으로 쪼갠 것을 API로 흘리지 않으려는
	// 것이므로 이 정규화는 사건 목록도 같은 규칙으로 쓴다(041 plan §API 계약 변경).
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
