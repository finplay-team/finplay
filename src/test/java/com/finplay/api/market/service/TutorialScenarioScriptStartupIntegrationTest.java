// 저작된 두 대본이 실제 애플리케이션 기동에서 로더의 검증을 통과하는지 확인한다.
package com.finplay.api.market.service;

import com.finplay.api.market.domain.TutorialScenarioScriptId;
import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.Market;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// **대본 정합성은 기동 시점에서만 잡힌다**(049 tasks §1 검증). 로더 생성자가 모든 대본 파일을 읽고
// 검증하므로, 어느 한쪽이 깨지면 컨텍스트 자체가 뜨지 않는다 — 그 실패를 배포가 아니라 여기서 만난다.
// 슬라이스 테스트로 대신하지 않는 이유는 실제 조립된 컨텍스트에서만 이 생성자가 도는 것을 보장하기
// 때문이다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TutorialScenarioScriptStartupIntegrationTest {

	@Autowired
	private TutorialScenarioScriptLoader loader;

	@Test
	void everyAuthoredScriptPassesStartupValidation() {
		assertThat(TutorialScenarioScriptId.values()).hasSize(2);
		assertThat(TutorialScenarioScriptId.values()).allSatisfy(scriptId -> {
			TutorialScenarioScript script = loader.script(scriptId);
			assertThat(script.market()).isEqualTo(scriptId.market());
			assertThat(script.version()).isEqualTo(TutorialPriceGenerator.VERSION_2);
			assertThat(script.basePrice()).isPositive();
			assertThat(script.stages()).isNotEmpty();
		});
		assertThat(loader.hasScript(Market.CRYPTO)).isTrue();
		assertThat(loader.firstScriptId(Market.CRYPTO))
			.isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	}
}
