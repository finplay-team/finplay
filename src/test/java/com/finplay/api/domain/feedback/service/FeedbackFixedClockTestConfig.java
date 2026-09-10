package com.finplay.api.domain.feedback.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

abstract class FeedbackFixedClockTestConfig {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");

	protected abstract LocalDateTime viewAt();

	@Bean
	@Primary
	Clock fixedClock() {
		return Clock.fixed(viewAt().atZone(KST).toInstant(), KST);
	}

	@Bean
	@Primary
	FakeNarrativeGenerator fakeNarrativeGenerator() {
		return new FakeNarrativeGenerator();
	}
}
