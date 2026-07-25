// ClockConfig가 제공하는 운영 Clock 빈과 고정 Clock 테스트 패턴을 검증하는 단위 테스트
package com.finplay.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

	private final ClockConfig clockConfig = new ClockConfig();

	@Test
	@DisplayName("운영 Clock 빈의 ZoneId는 Asia/Seoul이다")
	void clockBeanUsesAsiaSeoulZone() {
		Clock clock = clockConfig.clock();

		assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
	}

	@Test
	@DisplayName("Clock.fixed로 교체하면 시간 의존 로직을 특정 시각으로 고정해 검증할 수 있다")
	void fixedClockProducesExpectedLocalDateTime() {
		// 시간 의존 테스트 작성 시 참고할 패턴: 운영 Clock 대신 Clock.fixed(...)를 주입해
		// LocalDateTime.now(clock) 등 시간 의존 로직을 특정 시각으로 재현한다.
		ZoneId seoul = ZoneId.of("Asia/Seoul");
		LocalDateTime expected = LocalDateTime.of(2026, 7, 25, 10, 30, 0);
		Clock fixedClock = Clock.fixed(expected.atZone(seoul).toInstant(), seoul);

		LocalDateTime actual = LocalDateTime.now(fixedClock);

		assertThat(actual).isEqualTo(expected);
	}
}
