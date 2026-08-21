// 2단계(주문 방법 학습) 대본이 049 plan의 구조·극값·사건 부재를 만족하는지 파일을 읽어 판정한다.
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

// 2단계 대본은 이야기가 아니라 **주문 연습대**다 — 사건이 없고 한 구간이 오르내림을 반복한다.
// 안내 범위(3번 항목)와 지정가 연습은 극값이 정확히 88,000원·112,000원이라는 전제 위에 서므로
// 그 값이 흔들리면 "범위 밖 지정가는 안 체결된다"는 학습 자체가 무너진다.
class TutorialOrderBasicsScriptIntegrityTest {

	private static final String STAGE_ID = "ORDER_BASICS";
	private static final int STAGE_MINUTES = 160;
	private final TutorialPriceGenerator generator = new TutorialPriceGenerator();
	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	private final TutorialPriceGenerationInput input = new TutorialPriceGenerationInput(
		TutorialPriceGenerator.VERSION_2, 123_456_789L, 42L, 3L, Market.CRYPTO, LocalDate.of(2026, 8, 14));

	@Test
	void scriptIsOneProgressStageOfOneHundredSixtyMinutesWithNoEvents() {
		assertThat(script.version()).isEqualTo(TutorialPriceGenerator.VERSION_2);
		assertThat(script.market()).isEqualTo(Market.CRYPTO);
		assertThat(script.basePrice()).isEqualByComparingTo("100000.00000000");
		assertThat(script.stages()).singleElement().satisfies(stage -> {
			assertThat(stage.id()).isEqualTo(STAGE_ID);
			assertThat(stage.act()).isNull();
			assertThat(stage.kind()).isEqualTo(TutorialScenarioStageKind.PROGRESS);
			assertThat(stage.minutes()).isEqualTo(STAGE_MINUTES);
			assertThat(stage.ratios()).hasSize(STAGE_MINUTES);
		});
		// 사건이 없다는 것이 이 대본의 판정식이다 — 3번 항목의 안내 범위가 events().isEmpty()로 갈린다.
		assertThat(script.events()).isEmpty();
	}

	@Test
	void extremePricesAreExactlyEightyEightAndOneHundredTwelveThousandAndAreReachedEightTimesEach() {
		List<BigDecimal> prices = prices();

		assertThat(prices).hasSize(STAGE_MINUTES);
		assertThat(prices.stream().min(BigDecimal::compareTo).orElseThrow())
			.isEqualByComparingTo("88000.00000000");
		assertThat(prices.stream().max(BigDecimal::compareTo).orElseThrow())
			.isEqualByComparingTo("112000.00000000");
		assertThat(prices.stream().filter(price -> price.compareTo(new BigDecimal("88000.00000000")) == 0).count())
			.as("저점 도달 횟수")
			.isEqualTo(8L);
		assertThat(prices.stream().filter(price -> price.compareTo(new BigDecimal("112000.00000000")) == 0).count())
			.as("고점 도달 횟수")
			.isEqualTo(8L);
	}

	// 시작 가격이 기준가와 다르면 사용자가 첫 화면에서 보는 값과 안내 범위의 중심이 어긋난다.
	@Test
	void scriptStartsAtItsBasePrice() {
		assertThat(prices().get(0)).isEqualByComparingTo("100000.00000000");
	}

	private List<BigDecimal> prices() {
		return java.util.stream.IntStream.range(0, STAGE_MINUTES)
			.mapToObj(minute -> generator.canonicalPrice(input, script, new TutorialScenarioCursor(STAGE_ID, minute)))
			.toList();
	}
}
