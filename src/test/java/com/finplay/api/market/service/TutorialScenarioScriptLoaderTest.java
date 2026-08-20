// 깨진 대본이 기동을 실패시키는지, 정상 대본은 그대로 읽히는지 검증한다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.market.domain.Market;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

// 대본 검증을 기동 시점에 두는 이유는 잘못된 대본으로 서비스가 뜨면 사용자가 깨진 이야기를 겪게 되고
// 그 시점에는 되돌릴 방법이 없기 때문이다(041 plan §오류 계약).
class TutorialScenarioScriptLoaderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	// 로더를 직접 생성하는 테스트만 두면 주입 자체가 깨져도 드러나지 않는다 — 실제로 이 클래스가 Jackson 2
	// ObjectMapper를 받게 작성돼 컨텍스트가 뜨지 않은 적이 있다. Boot가 등록하는 빈으로 실제 조립해 본다.
	@Test
	void loaderIsWiredWithTheObjectMapperBootProvides() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
			.withUserConfiguration(TutorialScenarioScriptLoader.class)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(TutorialScenarioScriptLoader.class);
				assertThat(context
					.getBean(TutorialScenarioScriptLoader.class)
					.script(TutorialScenarioScriptId.CRYPTO_STORY_V1)
					.stages())
					.hasSize(8);
			});
	}

	@Test
	void loadsAuthoredCryptoScript() {
		TutorialScenarioScript script = new TutorialScenarioScriptLoader(objectMapper)
			.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);

		assertThat(script.version()).isEqualTo(TutorialPriceGenerator.VERSION_2);
		assertThat(script.market()).isEqualTo(Market.CRYPTO);
	}

	// 049 1번이 대본을 둘로 늘렸다 — 하나만 읽히고 다른 하나가 조용히 빠지면 2단계가 없는 채로 뜬다.
	@Test
	void loadsEveryAuthoredScriptIdWithItsOwnBasePrice() {
		TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(objectMapper);

		assertThat(TutorialScenarioScriptId.values())
			.allSatisfy(scriptId -> assertThat(loader.script(scriptId).market()).isEqualTo(scriptId.market()));
		assertThat(loader.script(TutorialScenarioScriptId.CRYPTO_STORY_V1).basePrice())
			.isEqualByComparingTo("10000.00000000");
		assertThat(loader.script(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1).basePrice())
			.isEqualByComparingTo("100000.00000000");
	}

	// 종목 선택이 박는 첫 대본이다. 순서가 뒤집히면 사용자가 2단계를 건너뛰고 3단계 이야기부터 만난다.
	@Test
	void cryptoStartsAtTheOrderBasicsScript() {
		assertThat(new TutorialScenarioScriptLoader(objectMapper).firstScriptId(Market.CRYPTO))
			.isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	}

	@Test
	void rejectsUnknownScriptId() {
		TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(objectMapper);

		assertThatThrownBy(() -> loader.script(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("대본이 저작되지 않은 식별자");
	}

	@Test
	void rejectsMarketWithoutAuthoredScript() {
		TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(objectMapper);

		assertThatThrownBy(() -> loader.firstScriptId(Market.STOCK))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("대본이 저작되지 않은 시장");
	}

	@Test
	void acceptsMinimalValidScript() {
		assertThat(load("valid.json").stages()).hasSize(2);
	}

	@Test
	void failsWhenScriptFileIsMissing() {
		assertThatThrownBy(() -> load("does-not-exist.json"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("찾을 수 없습니다");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"wrong-version.json",
		"wrong-market.json",
		"ratio-count-mismatch.json",
		"loop-endpoints-differ.json",
		"stage-boundary-gap.json",
		"unknown-event-stage.json",
		"reveal-delay-zero.json",
		"event-impact-overflows.json",
		// 마지막 구간이 대기 루프면 거기서 보유가 생긴 사용자는 나갈 진행 구간이 없어 커서가 영구 정지한다.
		"last-stage-is-loop.json",
		// 기준가가 없거나 0이면 모든 배율이 0원에 곱해져 대본 전체가 무의미해진다(049 ORDERBASICS-003).
		"missing-base-price.json",
		"zero-base-price.json"
	})
	void failsFastOnBrokenScript(String fileName) {
		assertThatThrownBy(() -> load(fileName))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("튜토리얼 대본이 올바르지 않습니다");
	}

	private TutorialScenarioScript load(String fileName) {
		return TutorialScenarioScriptLoader.load(objectMapper, Market.CRYPTO, "/tutorial-broken/" + fileName);
	}
}
