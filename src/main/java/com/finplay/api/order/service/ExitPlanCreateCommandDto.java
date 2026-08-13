// OCO 생성 엔진에 넘기는 내부 command — 입력 검증·경로 분기를 끝낸 호출부가 조립한다
package com.finplay.api.order.service;

import com.finplay.api.auth.domain.User;
import com.finplay.api.portfolio.domain.Holding;
import java.math.BigDecimal;

/**
 * {@code holding}은 호출부가 소유권(타인 소유는 404)과 시장 범위(코인 전용)를 이미 검증해 넘긴 엔티티다. 엔진은
 * 이 holding을 다시 잠그고 예약·저장만 수행한다(021 plan.md "일반 경로 검증 순서" 3~9단계).
 *
 * <p>{@code educationalOrigin}이 null이면 일반 경로, non-null이면 교육 경로다 — 엔진은 이 값을 해석하지 않고
 * plan snapshot에 그대로 저장한다.
 */
public record ExitPlanCreateCommandDto(User user, Holding holding, BigDecimal quantity, ExitPriceInputDto priceInput,
	String requestHash, ExitPlanEducationalOriginDto educationalOrigin) {

	public ExitPlanCreateCommandDto {
		if (user == null || holding == null || priceInput == null || requestHash == null) {
			throw new IllegalArgumentException("user·holding·priceInput·requestHash는 필수입니다.");
		}
		if (quantity == null || quantity.signum() <= 0) {
			throw new IllegalArgumentException("quantity는 0보다 커야 합니다.");
		}
	}

	// 일반 경로(intentionId 생략) — entryPrice는 생성 시점 holding.averagePrice snapshot이다(021 RISK-OCO-007).
	public static ExitPlanCreateCommandDto general(
		User user, Holding holding, BigDecimal quantity, ExitPriceInputDto priceInput, String requestHash) {
		return new ExitPlanCreateCommandDto(user, holding, quantity, priceInput, requestHash, null);
	}

	// 교육 경로(intentionId 지정) — entryPrice는 buyTrade의 불변 체결가다(019 EXIT-PRICE-004).
	public static ExitPlanCreateCommandDto educational(
		User user, Holding holding, BigDecimal quantity, ExitPriceInputDto priceInput, String requestHash,
		ExitPlanEducationalOriginDto educationalOrigin) {
		if (educationalOrigin == null) {
			throw new IllegalArgumentException("교육 경로는 educationalOrigin이 필수입니다.");
		}
		return new ExitPlanCreateCommandDto(user, holding, quantity, priceInput, requestHash, educationalOrigin);
	}

	public boolean isEducationalPath() {
		return educationalOrigin != null;
	}
}
