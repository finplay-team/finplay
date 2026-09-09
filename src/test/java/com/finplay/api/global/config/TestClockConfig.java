package com.finplay.api.global.config;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime PLACEHOLDER = LocalDateTime.of(2000, 1, 1, 0, 0);

	@Bean
	@Primary
	TestClock testClock() {
		return new TestClock(PLACEHOLDER.atZone(KST).toInstant(), KST);
	}
}
