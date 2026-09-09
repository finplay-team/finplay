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

@Component
public final class TutorialScenarioScriptLoader {

	private final Map<TutorialScenarioScriptId, TutorialScenarioScript> scripts;

	public TutorialScenarioScriptLoader(ObjectMapper objectMapper) {
		Map<TutorialScenarioScriptId, TutorialScenarioScript> loaded = new EnumMap<>(TutorialScenarioScriptId.class);
		for (TutorialScenarioScriptId scriptId : TutorialScenarioScriptId.values()) {
			loaded.put(scriptId, load(objectMapper, scriptId.market(), scriptId.resourcePath()));
		}
		this.scripts = Map.copyOf(loaded);
	}

	public boolean hasScript(Market market) {
		return TutorialScenarioScriptId.hasAny(market);
	}

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
			require(
				stage.kind() != TutorialScenarioStageKind.LOOP
					|| stage.ratios().get(0).compareTo(stage.ratios().get(stage.minutes() - 1)) == 0,
				resourcePath,
				"대기 구간의 첫 배율과 끝 배율이 다릅니다: " + stage.id());
		}

		require(
			script.stages().get(script.stages().size() - 1).kind() == TutorialScenarioStageKind.PROGRESS,
			resourcePath,
			"마지막 구간이 진행 구간이 아닙니다.");

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
				event.impactStartMinute() >= 0 && event.impactMinutes() > 0 && event.revealDelayMinutes() > 0,
				resourcePath,
				"사건의 영향·공개 지연 값이 올바르지 않습니다: " + event.stageId());
			require(
				event.impactStartMinute() + event.impactMinutes() <= script.stage(event.stageId()).minutes(),
				resourcePath,
				"사건의 영향 구간이 구간 길이를 넘습니다: " + event.stageId());
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
