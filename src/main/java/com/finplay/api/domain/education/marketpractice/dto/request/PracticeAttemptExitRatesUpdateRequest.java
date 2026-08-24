// 현재 실행 세대의 손절·익절 비율 자유 입력 요청 (052)
package com.finplay.api.domain.education.marketpractice.dto.request;

import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 두 값은 <b>퍼센트 수</b>이고(3%는 {@code 3}) <b>손절률도 양수</b>로 받는다 — 부호는 서버가 붙인다
 * (042 {@code ExitPreset}과 같은 단위·같은 부호 규칙).
 *
 * <p><b>두 값은 서로 독립이다.</b> 손절 5 + 익절 3처럼 프리셋 3개 어디에도 없는 조합도 유효하며, 여기에
 * 조합 검증을 넣지 않는다 — 실전 화면처럼 각자 정하게 하는 것이 052의 목적이다.
 *
 * <p><b>구간 리터럴이 {@link ExitRates}의 상수와 중복된다.</b> 애노테이션 인자는 컴파일 상수여야 해서
 * {@code BigDecimal} 상수를 참조할 수 없기 때문이다. 구간을 조정할 때 두 곳을 함께 고쳐야 하며, 여기만
 * 넓히면 {@link ExitRates}의 생성자 검증이 400이 아니라 500으로 드러난다.
 *
 * <p>{@code @Digits(fraction = 1)}이 소수 둘째 자리 이하를 막는다 — {@code 3.05}는 물론 값이 같은
 * {@code 3.00}도 거부된다(Bean Validation은 표기된 scale로 판정한다). 위반은 모두 400
 * {@code VALIDATION_ERROR}다.
 */
public record PracticeAttemptExitRatesUpdateRequest(
	@NotNull(message = "손절 비율은 필수입니다.") @DecimalMin(value = "2", message = "손절 비율은 2% 이상이어야 합니다.") @DecimalMax(value = "5", message = "손절 비율은 5% 이하여야 합니다.") @Digits(integer = 1, fraction = 1, message = "손절 비율은 소수 첫째 자리까지 입력할 수 있습니다.")
	BigDecimal stopLossRate,

	@NotNull(message = "익절 비율은 필수입니다.") @DecimalMin(value = "3", message = "익절 비율은 3% 이상이어야 합니다.") @DecimalMax(value = "8", message = "익절 비율은 8% 이하여야 합니다.") @Digits(integer = 1, fraction = 1, message = "익절 비율은 소수 첫째 자리까지 입력할 수 있습니다.")
	BigDecimal takeProfitRate) {

	public ExitRates toExitRates() {
		return ExitRates.of(stopLossRate, takeProfitRate);
	}
}
