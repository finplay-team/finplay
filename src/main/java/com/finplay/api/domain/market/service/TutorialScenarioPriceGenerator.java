package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TutorialScenarioPriceGenerator {

	private static final int PRICE_SCALE = 8;

	private TutorialScenarioPriceGenerator() {}

	public static BigDecimal canonicalPrice(
		TutorialScenarioScript script, TutorialScenarioCursor cursor, BigDecimal basePrice) {
		TutorialScenarioStage stage = script.stage(cursor.stageId());
		if (cursor.stageMinute() < 0 || cursor.stageMinute() >= stage.minutes()) {
			throw new IllegalArgumentException("대본 구간을 벗어난 위치입니다: " + cursor);
		}
		return basePrice.multiply(stage.ratios().get(cursor.stageMinute())).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
	}
}
