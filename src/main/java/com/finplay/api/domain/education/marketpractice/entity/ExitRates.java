// 튜토리얼 손절·익절 비율 한 쌍과 자유 입력 허용 구간을 담는 값 객체 (052)
package com.finplay.api.domain.education.marketpractice.entity;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 042는 손절·익절 기준을 이름 붙인 보기 3개({@link ExitPreset})로 고정했다. 052가 그 자리를 실전 화면과 같은
 * <b>자유 입력</b>으로 바꾸면서, "적용될 비율"의 단일 표현이 열거형이 아니라 이 값 객체가 됐다.
 *
 * <p><b>단위는 042 그대로다 — 퍼센트 수</b>(3%는 {@code 0.03}이 아니라 {@code 3})이고 <b>손절률도 양수</b>이며
 * 부호는 {@link com.finplay.api.education.marketpractice.service.ReferencePriceCalculator}가 붙인다. 분수로
 * 적으면 계산기가 다시 100으로 나눠 100배 틀린 값이 조용히 나온다.
 *
 * <p><b>두 값은 서로 독립이다.</b> 손절 5 + 익절 3처럼 프리셋 3개 어디에도 없는 조합이 유효하며, 프리셋
 * 조합만 허용하는 검증을 두지 않는다. {@link #matchingPreset()}은 그래서 <b>nullable</b>이다 — 프리셋 이름은
 * 이제 "우연히 그 조합과 같은가"의 표시일 뿐 저장 형태가 아니다.
 *
 * <p><b>구간은 서버가 정본이고 클라이언트에 내려보낸다</b>({@code exitRateBounds}, 042 EXITPRESET-009와 같은
 * 이유 — 화면이 하드코딩하면 서버가 구간을 조정할 때 조용히 어긋난다). 이 구간 전체가 041 대본에서 실제로
 * 손절·익절에 도달하는지는 {@code ExitPresetScenarioReachabilityTest}가 0.1%p 간격 전수로 고정한다 —
 * <b>사용자가 고를 수 있는데 대본이 영영 닿지 않는 값이 하나라도 있으면 그 사용자는 이 튜토리얼이 가르치려는
 * 것을 못 배우고 끝나기 때문이다.</b> 구간을 넓히려면 그 테스트를 함께 통과시켜야 한다.
 *
 * @param stopLossRate   손절률(퍼센트 수, 양수). 진입가의 −{@code stopLossRate}%를 뜻한다
 * @param takeProfitRate 익절률(퍼센트 수, 양수). 진입가의 +{@code takeProfitRate}%를 뜻한다
 */
public record ExitRates(BigDecimal stopLossRate, BigDecimal takeProfitRate) {

	public static final BigDecimal STOP_LOSS_MIN = new BigDecimal("2");
	public static final BigDecimal STOP_LOSS_MAX = new BigDecimal("5");

	/**
	 * <b>이 하한을 낮추지 마라.</b> 041 대본의 1막 고점은 배율 {@code 1.018}인데, 이 구간에서 나올 수 있는
	 * 가장 좁은 익절선은 (가장 낮은 진입 배율 {@code 0.998} 기준) {@code 1.02794}다 — <b>여유가
	 * 0.96%p뿐이다.</b> 대략 {@code 1.8} 아래로 내리면 1막에서 익절이 먼저 터지고, "손절을 먼저 겪은 뒤
	 * 재진입해서 익절한다"는 041의 학습 순서가 무너진다. 나머지 세 경계는 여유가 크다(익절 상한은
	 * {@code 15.8}까지, 손절 상한은 {@code 13.1}까지 3막·2막에서 여전히 닿는다).
	 *
	 * <p>근거는 추정이 아니라 전수 조사다 — {@code ExitPresetScenarioReachabilityTest}가 이 구간 전체를
	 * 0.1%p 간격(1581조합)으로, 가능한 모든 진입 분에 대해 훑어 <b>모든 조합에서 손절·익절이 실제로
	 * 도달하고 1막에서는 익절이 먼저 터지지 않는다</b>는 것을 고정한다. 구간을 넓히려면 그 테스트를 함께
	 * 통과시켜야 한다.
	 */
	public static final BigDecimal TAKE_PROFIT_MIN = new BigDecimal("3");
	public static final BigDecimal TAKE_PROFIT_MAX = new BigDecimal("8");

	/** 소수 첫째 자리까지만 받는다. 요청 본문 검증은 {@code @Digits}가 하고 여기서는 저장 정밀도만 정한다. */
	public static final int MAX_SCALE = 1;

	/**
	 * 아무것도 고르지 않은 사용자에게 적용되는 비율. 042의 기본 프리셋({@code BALANCED} = 손절 3·익절 5)과
	 * <b>같은 값이어야 한다</b> — 고르지 않은 사용자의 결과가 052 배포로 달라지면 안 된다
	 * (042 EXITPRESET-002 승계).
	 */
	public static final ExitRates DEFAULT = of(ExitPreset.DEFAULT);

	public ExitRates {
		if (stopLossRate == null || takeProfitRate == null) {
			throw new IllegalArgumentException("손절률과 익절률은 모두 필요합니다.");
		}
		requireWithin(stopLossRate, STOP_LOSS_MIN, STOP_LOSS_MAX, "손절률");
		requireWithin(takeProfitRate, TAKE_PROFIT_MIN, TAKE_PROFIT_MAX, "익절률");
		// DB가 DECIMAL(7,4)라 읽어 온 값은 scale 4(3.0000)다. 여기서 한 번 벗겨 두면 JSON에도 3으로 나가고
		// 프리셋 대조(matchingPreset)도 scale에 걸리지 않는다 — compareTo를 쓰지만, 같은 값이 응답마다 다른
		// 표기로 나가는 것부터 막는다.
		stopLossRate = stopLossRate.stripTrailingZeros();
		takeProfitRate = takeProfitRate.stripTrailingZeros();
	}

	public static ExitRates of(BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		return new ExitRates(stopLossRate, takeProfitRate);
	}

	/** 042 프리셋을 같은 값의 비율 쌍으로 옮긴다. 프리셋 선택 API가 남아 있는 동안 쓰인다. */
	public static ExitRates of(ExitPreset preset) {
		return new ExitRates(preset.stopLossRate(), preset.takeProfitRate());
	}

	/**
	 * 이 비율 쌍과 <b>정확히</b> 같은 042 프리셋. 없으면 {@code null}이다.
	 *
	 * <p>프론트가 프리셋 전환을 마칠 때까지 남겨 두는 {@code selectedExitPreset}·{@code entries[].exitPreset}이
	 * 이 판정으로 채워진다. {@code compareTo}로 비교하는 이유는 {@code 3}과 {@code 3.0}이
	 * {@code equals}로는 다르기 때문이다.
	 */
	public ExitPreset matchingPreset() {
		return Arrays.stream(ExitPreset.values())
			.filter(preset -> preset.stopLossRate().compareTo(stopLossRate) == 0
				&& preset.takeProfitRate().compareTo(takeProfitRate) == 0)
			.findFirst()
			.orElse(null);
	}

	private static void requireWithin(BigDecimal rate, BigDecimal min, BigDecimal max, String name) {
		if (rate.compareTo(min) < 0 || rate.compareTo(max) > 0) {
			throw new IllegalArgumentException(name + "은(는) " + min + "% 이상 " + max + "% 이하여야 합니다.");
		}
	}
}
