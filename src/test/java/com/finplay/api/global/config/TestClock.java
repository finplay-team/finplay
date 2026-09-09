package com.finplay.api.global.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class TestClock extends Clock {

	private final ZoneId zone;
	private volatile Instant instant;

	public TestClock(Instant instant, ZoneId zone) {
		this.instant = instant;
		this.zone = zone;
	}

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
