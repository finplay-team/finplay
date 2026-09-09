package com.finplay.api.domain.feedback.service;

import java.time.Duration;
import java.time.LocalTime;

public final class MarketSessionTimes {

	public static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);

	public static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);

	static final Duration ROLLING_WINDOW = Duration.ofHours(24);

	private MarketSessionTimes() {}
}
