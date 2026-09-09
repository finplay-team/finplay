package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BithumbFeedSimulatorDisabledIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void bithumbFeedSimulatorBeanIsAbsentWhenSimulateEnabledIsDisabledForTests() {
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedSimulator.class)).isEmpty();
	}
}
