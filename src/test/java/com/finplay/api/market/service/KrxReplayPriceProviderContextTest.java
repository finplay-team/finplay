// StockFeedConfig 삭제(이슈 #19) 이후 KrxReplayPriceProvider가 @Service로 Spring 컨텍스트에 정상 등록되어
// StockPriceProvider 빈이 정확히 하나임을 확인하는 최소 컨텍스트 테스트 — 선택 설정이 없으므로 구현체가 하나뿐임을 검증한다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class KrxReplayPriceProviderContextTest {

	@Autowired
	private StockPriceProvider stockPriceProvider;

	// 후보 빈이 둘 이상이면 @Autowired 단일 필드 주입 자체가 실패하므로, 이 테스트가 통과하면
	// StockPriceProvider 빈이 정확히 하나이고 그것이 KrxReplayPriceProvider임이 보장된다.
	@Test
	void stockPriceProviderBeanIsRegisteredAsKrxReplayPriceProvider() {
		assertThat(stockPriceProvider).isInstanceOf(KrxReplayPriceProvider.class);
	}
}
