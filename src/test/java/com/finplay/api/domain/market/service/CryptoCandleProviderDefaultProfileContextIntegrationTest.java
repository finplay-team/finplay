package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CryptoCandleProviderDefaultProfileContextIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private CryptoCandleProvider cryptoCandleProvider;

	@Test
	void defaultProfileStillRegistersOnlyFakeCryptoCandleProvider() {
		assertThat(cryptoCandleProvider).isInstanceOf(FakeCryptoCandleProvider.class);
		assertThat(applicationContext.getBeanNamesForType(BithumbRestCandleProvider.class)).isEmpty();
	}
}
