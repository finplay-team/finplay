package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class KisHistoricalReplayPriceProviderContextTest {

	@Autowired
	private StockPriceProvider stockPriceProvider;

	@Test
	void stockPriceProviderBeanIsRegisteredAsKisHistoricalReplayPriceProvider() {
		assertThat(stockPriceProvider).isInstanceOf(KisHistoricalReplayPriceProvider.class);
	}
}
