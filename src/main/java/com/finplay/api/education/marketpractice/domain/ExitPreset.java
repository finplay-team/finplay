// 튜토리얼 손절·익절 기준을 이름 붙인 보기 3개로 고정하는 열거형 (042 EXITPRESET-001)
package com.finplay.api.education.marketpractice.domain;

import java.math.BigDecimal;

/**
 * 손절률·익절률은 퍼센트 수다(3%는 {@code 0.03}이 아니라 {@code 3}). {@code exit_plans.stop_loss_rate}
 * DECIMAL(7,4)와 {@link com.finplay.api.education.marketpractice.service.ReferencePriceCalculator}가 쓰는
 * 단위를 그대로 따른다 — 분수로 적으면 계산기가 다시 100으로 나눠 100배 틀린 값이 조용히 나온다.
 *
 * <p><b>세 수치는 임의로 고른 것이 아니다.</b> {@code BALANCED}는 이 기능 도입 전의 서버 상수
 * {@code ×0.97}·{@code ×1.05}와 정확히 같아야 하고(EXITPRESET-002), 나머지 둘은 041 대본이 허용하는 구간
 * 안에서 고른 값이다(그 구간에는 다른 값도 들어간다 — 유일해가 아니다). <b>임의 조정 대상이 아닌 것은 세
 * 손절률의 1%p 간격</b>이다 — 2막 루머 저점 {@code 0.975}가 {@code CAUTIOUS} 손절선 구간과
 * {@code BALANCED} 손절선 구간 사이에 들어가야 하고 여유가 각 0.3%p뿐이다(041 plan §프리셋 도달 조건 검증).
 * 두 문서가 조용히 어긋나는 것은 {@code ExitPresetScenarioReachabilityTest}가 막는다.
 *
 * <p>표시 이름(조심스럽게·보통·느긋하게)은 여기에 두지 않는다 — plan §API 계약의
 * {@code availableExitPresets}가 식별자·손절률·익절률만 내려보내고 문구는 클라이언트가 갖는다.
 */
public enum ExitPreset {

	/** 조심스럽게 — 2막 루머에서 유일하게 손절된다. */
	CAUTIOUS("2", "3"),
	/** 보통 — 기본값이며 현행 −3%·+5%와 같다. */
	BALANCED("3", "5"),
	/** 느긋하게 — 루머와 속임수 반등을 버티고 2막 확정에서 손절된다. */
	RELAXED("5", "8");

	/**
	 * 사용자가 아무것도 고르지 않았을 때 적용되는 프리셋. {@code practice_attempts.exit_preset}·
	 * {@code practice_risk_snapshots.exit_preset}이 null인 행도 이 값으로 해석한다(EXITPRESET-002).
	 */
	public static final ExitPreset DEFAULT = BALANCED;

	private final BigDecimal stopLossRate;
	private final BigDecimal takeProfitRate;

	ExitPreset(String stopLossRate, String takeProfitRate) {
		this.stopLossRate = new BigDecimal(stopLossRate);
		this.takeProfitRate = new BigDecimal(takeProfitRate);
	}

	/** 손절률(퍼센트 수). {@code CAUTIOUS}는 {@code 2}이며 진입가의 −2%를 뜻한다. */
	public BigDecimal stopLossRate() {
		return this.stopLossRate;
	}

	/** 익절률(퍼센트 수). {@code CAUTIOUS}는 {@code 3}이며 진입가의 +3%를 뜻한다. */
	public BigDecimal takeProfitRate() {
		return this.takeProfitRate;
	}
}
