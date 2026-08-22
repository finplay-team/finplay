// 손절·익절 비율 자유 입력의 허용 구간을 클라이언트에 내려보내는 응답 DTO (052)
package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import java.math.BigDecimal;

/**
 * 값은 퍼센트 수이며 양 끝을 <b>포함</b>한다(손절 {@code 2}~{@code 5}, 익절 {@code 3}~{@code 8}).
 *
 * <p><b>서버가 내려보내는 이유는 화면이 구간을 하드코딩하지 않게 하기 위해서다</b> — 042 EXITPRESET-009가
 * {@code availableExitPresets}를 내려보낸 것과 같은 이유다. 구간이 조정되면 슬라이더·입력 제한이 배포 없이
 * 따라간다.
 */
public record ExitRateBoundsResponse(
	BigDecimal stopLossMin, BigDecimal stopLossMax, BigDecimal takeProfitMin, BigDecimal takeProfitMax) {

	private static final ExitRateBoundsResponse CURRENT = new ExitRateBoundsResponse(
		ExitRates.STOP_LOSS_MIN, ExitRates.STOP_LOSS_MAX, ExitRates.TAKE_PROFIT_MIN, ExitRates.TAKE_PROFIT_MAX);

	/** 고정값이라 매 요청 새로 만들지 않는다. record라 불변이므로 인스턴스를 공유해도 안전하다. */
	public static ExitRateBoundsResponse current() {
		return CURRENT;
	}
}
