// 대본 위치를 가격으로 바꾸는 생성기 버전 2의 고정 벡터와 경계 처리를 검증한다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TutorialScenarioPriceGeneratorTest {

	private static final BigDecimal CRYPTO_BASE_PRICE = new BigDecimal("10000.00000000");

	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);

	// 대본 배율이 바뀌면 이 벡터가 깨진다. 각 구간의 시작·극값 지점을 대표로 골랐다.
	@ParameterizedTest
	@CsvSource({
		"IDLE_ENTRY, 0, 10000.00000000",
		"IDLE_ENTRY, 4, 10020.00000000",
		"IDLE_ENTRY, 15, 9980.00000000",
		"ACT1_RISE, 6, 10138.26000000",
		"ACT1_RISE, 14, 10180.00000000",
		"ACT2_RUMOR, 7, 9750.00000000",
		"ACT2_FAKEOUT, 4, 9950.00000000",
		"ACT2_CONFIRM, 11, 8700.00000000",
		"IDLE_REENTRY, 11, 8680.00000000",
		"ACT3_REBOUND, 24, 10100.00000000",
		"ACT4_CRASH, 19, 7900.00000000"
	})
	void matchesVersionTwoGoldenVector(String stageId, int stageMinute, String expectedPrice) {
		BigDecimal price = TutorialScenarioPriceGenerator.canonicalPrice(
			script, new TutorialScenarioCursor(stageId, stageMinute), CRYPTO_BASE_PRICE);

		assertThat(price).isEqualByComparingTo(expectedPrice);
		assertThat(price).hasScaleOf(8);
	}

	@Test
	void rejectsUnknownStage() {
		assertThatThrownBy(() -> TutorialScenarioPriceGenerator.canonicalPrice(
			script, new TutorialScenarioCursor("ACT9_UNKNOWN", 0), CRYPTO_BASE_PRICE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("대본에 없는 구간");
	}

	@ParameterizedTest
	@CsvSource({"-1", "8"})
	void rejectsMinuteOutsideStage(int stageMinute) {
		assertThatThrownBy(() -> TutorialScenarioPriceGenerator.canonicalPrice(
			script, new TutorialScenarioCursor("ACT2_RUMOR", stageMinute), CRYPTO_BASE_PRICE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("대본 구간을 벗어난 위치");
	}
}
