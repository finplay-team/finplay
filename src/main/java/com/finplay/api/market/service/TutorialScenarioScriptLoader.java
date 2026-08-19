// 시장별 튜토리얼 대본 파일을 기동 시 1회 읽어 정합성을 검증하고 불변 객체로 보관하는 컴포넌트
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Market;
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

	private static final Map<Market, String> SCRIPT_RESOURCE_PATHS = Map.of(Market.CRYPTO,
		"/tutorial/scenario-crypto-v1.json");

	private final Map<Market, TutorialScenarioScript> scripts;

	// 생성자는 하나만 둔다 — 둘이면 Spring이 어느 쪽으로 주입할지 정하지 못해 빈 생성 자체가 실패한다.
	// 깨진 대본으로 기동이 실패하는지 검증하는 테스트는 아래 load를 직접 부른다.
	public TutorialScenarioScriptLoader(ObjectMapper objectMapper) {
		Map<Market, TutorialScenarioScript> loaded = new EnumMap<>(Market.class);
		SCRIPT_RESOURCE_PATHS.forEach((market, path) -> loaded.put(market, load(objectMapper, market, path)));
		this.scripts = Map.copyOf(loaded);
	}

	public TutorialScenarioScript script(Market market) {
		TutorialScenarioScript script = scripts.get(market);
		if (script == null) {
			throw new IllegalArgumentException("대본이 저작되지 않은 시장입니다: " + market);
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
		}
	}

	private static void require(boolean condition, String resourcePath, String message) {
		if (!condition) {
			throw new IllegalStateException("튜토리얼 대본이 올바르지 않습니다(" + resourcePath + "): " + message);
		}
	}
}
