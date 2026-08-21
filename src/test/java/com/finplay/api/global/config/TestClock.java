// 테스트가 시각을 고정하거나 도중에 전진시킬 수 있는 Clock 구현 (통합 테스트 공용, 이슈 #134)
package com.finplay.api.global.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 통합 테스트가 전역 {@link Clock} 빈(ClockConfig, Asia/Seoul 실시각) 대신 쓰는 조작 가능한 시계다.
 *
 * <p>같은 구현이 22개 테스트 파일에 각자 중첩 클래스로 복사돼 있었고, 그 설정 클래스들의 이름이 서로 달라
 * Spring이 테스트마다 별개 컨텍스트로 취급했다(이슈 #134에서 실측 — 컨텍스트 82갈래 중 31갈래가 이 부류).
 * 하나로 합쳐 {@link TestClockConfig}로만 노출한다.
 *
 * <p><b>테스트마다 {@code @BeforeEach}에서 {@link #set(LocalDateTime)}으로 기준 시각을 세운다.</b>
 * 컨텍스트를 공유하므로 이 시계도 테스트 클래스들 사이에서 공유되고, 세우지 않으면 직전 클래스가 남긴
 * 시각을 그대로 물려받는다. 테스트는 순차 실행되므로(build.gradle에 병렬 포크 설정 없음) 세우기만 하면
 * 서로 간섭하지 않는다.
 */
public final class TestClock extends Clock {

	private final ZoneId zone;
	private volatile Instant instant;

	public TestClock(Instant instant, ZoneId zone) {
		this.instant = instant;
		this.zone = zone;
	}

	/** 기준 시각을 세운다. 존은 이 시계의 존(Asia/Seoul)으로 해석한다. */
	public void set(LocalDateTime localDateTime) {
		this.instant = localDateTime.atZone(zone).toInstant();
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new TestClock(instant, zone);
	}

	@Override
	public Instant instant() {
		return instant;
	}
}
