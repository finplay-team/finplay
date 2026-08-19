// 대본 위치(구간·구간 내 분)를 기준가 배율로 읽어 canonical 가격으로 바꾸는 순수 변환기
package com.finplay.api.market.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 가격을 절대값이 아니라 기준가 대비 배율로 적었기 때문에(041 plan §대본 설계) 기준가는 호출자가 넘긴다.
// 시장별 기준가는 생성기 버전 1과 공유하는 값이며 진입점인 TutorialPriceGenerator가 소유한다.
// 상태가 없는 순수 변환이라 빈으로 만들지 않는다 — 진입점이 이 클래스에 의존하면 생성기 버전 1만 쓰는
// 기존 호출부까지 대본 로더를 함께 들고 다녀야 한다.
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
