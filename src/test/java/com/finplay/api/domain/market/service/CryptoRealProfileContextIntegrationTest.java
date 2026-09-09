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

	@Autowired
	private CryptoCandleProvider cryptoCandleProvider;

	@Test
	void cryptoRealProfileExposesCachedProviderAsPrimaryWithRestProviderAsDelegate() {
		assertThat(cryptoCandleProvider).isInstanceOf(CachedCryptoCandleProvider.class);
		assertThat(applicationContext.getBeanNamesForType(BithumbRestCandleProvider.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FakeCryptoCandleProvider.class)).isEmpty();
	}

	@Test
	void cryptoRealProfileDoesNotRegisterBithumbFeedSimulator() {
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedSimulator.class)).isEmpty();
	}

	@Test
	void tickerPollerIsDisabledForTestsSoNoExternalCallHappens() {
		assertThat(applicationContext.getBeanNamesForType(BithumbRestTickerPoller.class)).isEmpty();
	}
}
