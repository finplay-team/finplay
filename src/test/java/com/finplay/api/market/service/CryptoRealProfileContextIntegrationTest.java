// crypto-real 프로필 전체 컨텍스트에서 코인 캔들 provider가 실제 빗썸 구현 하나뿐이고 시뮬레이터 빈이 사라지는지 검증한다 (이슈 #107 ⑪⑬)
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.feed.BithumbFeedSimulator;
import com.finplay.api.market.feed.BithumbRestTickerPoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("crypto-real")
class CryptoRealProfileContextIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	// 후보 빈이 둘이면(@Profile 조건이 겹치면) @Autowired 단일 필드 주입 자체가 실패하므로,
	// 이 필드가 주입되었다는 사실만으로 CryptoCandleProvider 구현이 정확히 하나임이 보장된다.
	@Autowired
	private CryptoCandleProvider cryptoCandleProvider;

	@Test
	void cryptoRealProfileRegistersOnlyBithumbRestCandleProvider() {
		assertThat(cryptoCandleProvider).isInstanceOf(BithumbRestCandleProvider.class);
		assertThat(applicationContext.getBeanNamesForType(FakeCryptoCandleProvider.class)).isEmpty();
	}

	// ⑬의 핵심 단언 — application-crypto-real.yml의 bithumb.feed.simulate.enabled=false가 실제로 먹어서
	// 합성 랜덤 틱을 내는 시뮬레이터 빈이 아예 생성되지 않아야 한다(캔들만 실데이터가 되는 조합이 생기면 안 된다).
	@Test
	void cryptoRealProfileDoesNotRegisterBithumbFeedSimulator() {
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedSimulator.class)).isEmpty();
	}

	// 자동 테스트는 외부 네트워크에 의존하지 않는다(PRD C-005) — 테스트에서는
	// bithumb-feed-simulator-disabled-for-tests.yml의 bithumb.feed.ticker.enabled=false가 폴러 빈을 막아야 한다.
	// 폴러가 여기서 생성되면 @Scheduled로 실제 빗썸을 3초마다 호출하게 되므로 그 격리 장치를 함께 고정한다.
	@Test
	void tickerPollerIsDisabledForTestsSoNoExternalCallHappens() {
		assertThat(applicationContext.getBeanNamesForType(BithumbRestTickerPoller.class)).isEmpty();
	}
}
