package com.finplay.api.domain.order.service;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.portfolio.entity.Holding;
import java.math.BigDecimal;

public record ExitPlanCreateCommandDto(User user, Holding holding, BigDecimal quantity, ExitPriceInputDto priceInput,
	String requestHash, ExitPlanEducationalOriginDto educationalOrigin, ExitPlanPracticeOriginDto practiceOrigin) {

	public ExitPlanCreateCommandDto {
		if (user == null || holding == null || priceInput == null || requestHash == null) {
			throw new IllegalArgumentException("user·holding·priceInput·requestHash는 필수입니다.");
		}
		if (quantity == null || quantity.signum() <= 0) {
			throw new IllegalArgumentException("quantity는 0보다 커야 합니다.");
		}
		if (educationalOrigin != null && practiceOrigin != null) {
			throw new IllegalArgumentException("교육 경로와 튜토리얼 자동 예약 경로는 함께 쓸 수 없습니다.");
		}
	}

	public static ExitPlanCreateCommandDto general(
		User user, Holding holding, BigDecimal quantity, ExitPriceInputDto priceInput, String requestHash) {
		return new ExitPlanCreateCommandDto(user, holding, quantity, priceInput, requestHash, null, null);
	}

	public static ExitPlanCreateCommandDto educational(
		User user, Holding holding, BigDecimal quantity, ExitPriceInputDto priceInput, String requestHash,
		ExitPlanEducationalOriginDto educationalOrigin) {
		if (educationalOrigin == null) {
			throw new IllegalArgumentException("교육 경로는 educationalOrigin이 필수입니다.");
		}
		return new ExitPlanCreateCommandDto(user, holding, quantity, priceInput, requestHash, educationalOrigin, null);
	}

	public static ExitPlanCreateCommandDto practice(
		User user, Holding holding, BigDecimal quantity, ExitPriceInputDto priceInput, String requestHash,
		ExitPlanPracticeOriginDto practiceOrigin) {
		if (practiceOrigin == null) {
			throw new IllegalArgumentException("튜토리얼 자동 예약 경로는 practiceOrigin이 필수입니다.");
		}
		return new ExitPlanCreateCommandDto(user, holding, quantity, priceInput, requestHash, null, practiceOrigin);
	}

	public boolean isEducationalPath() {
		return educationalOrigin != null;
	}

	public boolean isPracticePath() {
		return practiceOrigin != null;
	}
}
