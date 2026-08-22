// crypto-real 프로필 전체 컨텍스트에서 코인 캔들 provider 배선이 CachedCryptoCandleProvider(데코레이터, @Primary)
// + BithumbRestCandleProvider(위임 대상) 조합으로 고정되고 Fake·시뮬레이터 빈이 사라지는지 검증한다 (이슈 #107 ⑪⑬, MKT-010 이슈 #242)
package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.feed.BithumbFeedSimulator;
import com.finplay.api.domain.market.feed.BithumbRestTickerPoller;
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

	// MKT-010(이슈 #242) 이후 이 프로필에는 CryptoCandleProvider 구현이 둘(데코레이터+위임 대상) 공존한다 —
	// @Primary가 없으면 이 단일 필드 주입 자체가 모호성으로 실패하므로, 주입된 타입이 데코레이터인지를
	// 확인하는 것으로 "정확히 어떤 구현이 CandleQueryService에 노출되는가"를 고정한다.
	@Autowired
	private CryptoCandleProvider cryptoCandleProvider;

	@Test
	void cryptoRealProfileExposesCachedProviderAsPrimaryWithRestProviderAsDelegate() {
		assertThat(cryptoCandleProvider).isInstanceOf(CachedCryptoCandleProvider.class);
		// 위임 대상은 여전히 빈으로 존재한다 — 데코레이터가 구체 클래스로 직접 주입받는 대상이다.
		assertThat(applicationContext.getBeanNamesForType(BithumbRestCandleProvider.class)).hasSize(1);
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
