// 대본 식별자별 튜토리얼 대본 파일을 기동 시 1회 읽어 정합성을 검증하고 불변 객체로 보관하는 컴포넌트
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// 대본은 DB가 아니라 클래스패스 리소스다 — 문안 한 줄을 고치는 데 마이그레이션이 필요해지는 것을 피하기
// 위해서다(041 plan §대본 설계). 검증을 기동 시점에 두는 이유는 깨진 대본으로 서비스가 뜨면 사용자가 깨진
// 이야기를 겪게 되고 그 시점에는 되돌릴 방법이 없기 때문이다(041 plan §오류 계약).
// 생성자가 리소스 로딩 실패 시 예외를 던지므로(SpotBugs CT_CONSTRUCTOR_THROW) 클래스를 final로 선언한다.
@Component
public final class TutorialScenarioScriptLoader {

	// **구간 id는 사실상 스키마다.** 진행 중인 attempt가 practice_attempts.scenario_stage_id에 이 리터럴을
	// 들고 있으므로, 배포된 대본에서 id를 바꾸거나 지우면 그 사용자는 "대본에 없는 구간입니다"로 500에
	// 갇힌다(재시작 외에 회복 수단이 없다). 문안·배율은 재배포로 고칠 수 있지만 id는 고치지 않는다.
	// 파일 경로는 TutorialScenarioScriptId가 들고 있다 — 시장 하나에 대본이 여럿이라 Market은 키가 되지
	// 못한다(049 plan §1).
	private final Map<TutorialScenarioScriptId, TutorialScenarioScript> scripts;

	// 생성자는 하나만 둔다 — 둘이면 Spring이 어느 쪽으로 주입할지 정하지 못해 빈 생성 자체가 실패한다.
	// 깨진 대본으로 기동이 실패하는지 검증하는 테스트는 아래 load를 직접 부른다.
	public TutorialScenarioScriptLoader(ObjectMapper objectMapper) {
		Map<TutorialScenarioScriptId, TutorialScenarioScript> loaded = new EnumMap<>(TutorialScenarioScriptId.class);
		for (TutorialScenarioScriptId scriptId : TutorialScenarioScriptId.values()) {
			loaded.put(scriptId, load(objectMapper, scriptId.market(), scriptId.resourcePath()));
		}
		this.scripts = Map.copyOf(loaded);
	}

	// 대본이 저작된 시장에서만 생성기 버전 2를 쓴다 — 041은 CRYPTO 대본 하나만 저작했고 STOCK 대본은
	// SCENARIO-024의 후속이다. 호출부가 시장 목록을 따로 들고 있으면 STOCK 대본이 추가될 때 그 목록을
	// 함께 고치지 않아 조용히 버전 1에 머문다. 대본이 여럿이 된 뒤에도 "이 시장에 대본이 하나라도
	// 있는가"라는 의미는 그대로다.
	public boolean hasScript(Market market) {
		return TutorialScenarioScriptId.hasAny(market);
	}

	// 실행이 처음 서는 대본이다. 종목 선택 시점에 attempt에 박힌다(049 plan §2).
	public TutorialScenarioScriptId firstScriptId(Market market) {
		return TutorialScenarioScriptId.first(market);
	}

	public TutorialScenarioScript script(TutorialScenarioScriptId scriptId) {
		TutorialScenarioScript script = scriptId == null ? null : scripts.get(scriptId);
		if (script == null) {
			throw new IllegalArgumentException("대본이 저작되지 않은 식별자입니다: " + scriptId);
		}
		return script;
	}

	static TutorialScenarioScript load(ObjectMapper objectMapper, Market market, String resourcePath) {
		try (InputStream inputStream = TutorialScenarioScriptLoader.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				throw new IllegalStateException("튜토리얼 대본 파일을 찾을 수 없습니다: " + resourcePath);
			}
			TutorialScenarioScript script = objectMapper.readValue(inputStream, TutorialScenarioScript.class);
			validate(script, market, resourcePath);
			return script;
		} catch (IOException ex) {
			throw new IllegalStateException("튜토리얼 대본 파일을 읽는 중 오류가 발생했습니다: " + resourcePath, ex);
		}
	}

	private static void validate(TutorialScenarioScript script, Market market, String resourcePath) {
		require(script.version() == TutorialPriceGenerator.VERSION_2, resourcePath, "대본 버전이 2가 아닙니다.");
		require(script.market() == market, resourcePath, "대본의 시장이 파일 위치와 다릅니다.");
		// 기준가가 없으면 모든 배율이 0원에 곱해져 대본 전체가 무의미해진다(049 ORDERBASICS-003).
		require(
			script.basePrice() != null && script.basePrice().signum() > 0,
			resourcePath,
			"기준가가 비어 있거나 0 이하입니다.");
		require(!script.stages().isEmpty(), resourcePath, "구간이 하나도 없습니다.");

		Set<String> stageIds = new HashSet<>();
		for (TutorialScenarioStage stage : script.stages()) {
			require(stage.id() != null && !stage.id().isBlank(), resourcePath, "구간 id가 비어 있습니다.");
			require(stageIds.add(stage.id()), resourcePath, "구간 id가 중복됩니다: " + stage.id());
			require(stage.kind() != null, resourcePath, "구간 종류가 비어 있습니다: " + stage.id());
			require(stage.minutes() > 0, resourcePath, "구간 길이가 0 이하입니다: " + stage.id());
			require(
				stage.ratios().size() == stage.minutes(),
				resourcePath,
				"배율 개수가 구간 길이와 다릅니다: " + stage.id());
			require(
				stage.ratios().stream().allMatch(ratio -> ratio.compareTo(BigDecimal.ZERO) > 0),
				resourcePath,
				"배율이 0 이하입니다: " + stage.id());
			// LOOP 구간은 끝에 닿으면 처음으로 되감기므로 첫 배율과 끝 배율이 같아야 경계에서 가격이 튀지 않는다.
			require(
				stage.kind() != TutorialScenarioStageKind.LOOP
					|| stage.ratios().get(0).compareTo(stage.ratios().get(stage.minutes() - 1)) == 0,
				resourcePath,
				"대기 구간의 첫 배율과 끝 배율이 다릅니다: " + stage.id());
		}

		// 마지막 구간이 대기 루프면 그 구간에서 보유가 생긴 사용자는 나갈 다음 진행 구간이 없어 커서가
		// 영구 정지한다(진행 계산이 delta를 소비하지 못한 채 멈춘다). 현행 CRYPTO 대본은 마지막이
		// ACT4_CRASH라 도달 불가지만, STOCK 대본(SCENARIO-024)이 들어올 때 구조적으로 막아 둔다.
		require(
			script.stages().get(script.stages().size() - 1).kind() == TutorialScenarioStageKind.PROGRESS,
			resourcePath,
			"마지막 구간이 진행 구간이 아닙니다.");

		// 구간이 바뀌는 자리에서 배율이 튀면 사용자에게는 원인 없는 갭으로 보인다. 시장마다 대본이 하나씩
		// 늘어나도 이 검사가 함께 따라가도록 정합성 테스트가 아니라 기동 검증에 둔다(PR #469 리뷰 권장).
		for (int index = 0; index < script.stages().size() - 1; index++) {
			TutorialScenarioStage current = script.stages().get(index);
			TutorialScenarioStage next = script.stages().get(index + 1);
			require(
				current.ratios()
					.get(current.minutes() - 1)
					.compareTo(next.ratios().get(0)) == 0,
				resourcePath,
				"구간 경계에서 배율이 이어지지 않습니다: " + current.id() + " -> " + next.id());
		}

		for (TutorialScenarioEvent event : script.events()) {
			require(
				event.stageId() != null && stageIds.contains(event.stageId()),
				resourcePath,
				"대본에 없는 구간을 가리키는 사건이 있습니다: " + event.stageId());
			require(
				event.headline() != null && !event.headline().isBlank(),
				resourcePath,
				"사건 문안이 비어 있습니다: " + event.stageId());
			require(
				// 공개 지연이 0이면 원인이 가격과 동시에 열린다. spec §비즈니스 규칙은 두 값이 같아지는
				// 대본을 무효로 못박았다(SCENARIO-015) — 원인은 사후에만 열려야 한다.
				event.impactStartMinute() >= 0 && event.impactMinutes() > 0 && event.revealDelayMinutes() > 0,
				resourcePath,
				"사건의 영향·공개 지연 값이 올바르지 않습니다: " + event.stageId());
			require(
				event.impactStartMinute() + event.impactMinutes() <= script.stage(event.stageId()).minutes(),
				resourcePath,
				"사건의 영향 구간이 구간 길이를 넘습니다: " + event.stageId());
			// 공개 분이 구간을 벗어나면 그 사건은 **영영 열리지 않는다** — 커서가 구간 끝에 닿으면 다음
			// 구간으로 넘어가므로 공개 조건을 만족하는 순간이 없다. 041 6번이 revealMinute의 첫 소비자라
			// 여기서 처음 검증한다(어떤 테스트도 조용히 닫힌 사건을 잡지 못한다).
			require(
				event.revealMinute() < script.stage(event.stageId()).minutes(),
				resourcePath,
				"사건의 공개 분이 구간 길이를 넘습니다: " + event.stageId());
		}
	}

	private static void require(boolean condition, String resourcePath, String message) {
		if (!condition) {
			throw new IllegalStateException("튜토리얼 대본이 올바르지 않습니다(" + resourcePath + "): " + message);
		}
	}
}
