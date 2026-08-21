// 대본 배율 극값으로 안내 가격 범위를 계산하는 순수 함수의 단위 테스트(049 tasks 3번)
package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TutorialScenarioPriceGuideRangeCalculatorTest {

	private final TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(new ObjectMapper());

	// tasks.md 3번 검증 1행 — 2단계 대본(basePrice 100000, 배율 0.880~1.120)은 90000~110000이어야 한다.
	@Test
	void calculateReturnsNinetyToOneHundredTenThousandRangeForOrderBasicsScript() {
		TutorialScenarioScript script = loader.script(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

		Optional<TutorialScenarioPriceGuideRangeCalculator.Range> range = TutorialScenarioPriceGuideRangeCalculator
			.calculate(script);

		assertThat(range).isPresent();
		assertThat(range.get().low()).isEqualByComparingTo(new BigDecimal("90000.00000000"));
		assertThat(range.get().high()).isEqualByComparingTo(new BigDecimal("110000.00000000"));
	}

	// tasks.md 3번 검증 3행 — 폭이 좁아 low >= high가 되는 인공 대본은 예외 없이 범위 없음이어야 한다.
	// 모든 분의 배율이 동일해 span이 정확히 0인 대본(가격 변동이 전혀 없는 극단적 인공 대본)으로 재현한다.
	@Test
	void calculateReturnsNoRangeInsteadOfThrowingWhenScriptHasZeroPriceSpan() {
		TutorialScenarioStage flatStage = new TutorialScenarioStage(
			"FLAT", null, TutorialScenarioStageKind.PROGRESS, 2,
			List.of(new BigDecimal("1.000000"), new BigDecimal("1.000000")));
		TutorialScenarioScript flatScript = new TutorialScenarioScript(
			(short)2, Market.CRYPTO, new BigDecimal("10000.00000000"), List.of(flatStage), List.of());

		Optional<TutorialScenarioPriceGuideRangeCalculator.Range> range = TutorialScenarioPriceGuideRangeCalculator
			.calculate(flatScript);

		assertThat(range).isEmpty();
	}
}
