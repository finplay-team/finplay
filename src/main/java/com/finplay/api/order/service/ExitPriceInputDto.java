// OCO 가격선 계산에 넘기는 입력 snapshot — 019의 PRICE/PERCENT tagged union 불변식을 생성 시점에 강제한다
package com.finplay.api.order.service;

import com.finplay.api.order.domain.ExitPriceType;
import java.math.BigDecimal;

/**
 * {@code docs/specs/019-exit-price-policy} plan.md "계산 정책"이 요구한 "entity·request DTO에 의존하지 않는 내부
 * snapshot DTO"다. PRICE는 절대 가격 둘만, PERCENT는 rate 둘만 담는다는 union 불변식을 여기서 한 번 강제해
 * {@link ExitPricePolicy}와 호출부가 같은 검사를 중복하지 않게 한다.
 *
 * <p>여기서 던지는 {@link IllegalArgumentException}은 사용자 입력 오류가 아니라 호출부(경로별 필드 조합 검증
 * 담당)의 결함이다. 요청 필드 조합 위반은 400 {@code VALIDATION_ERROR}로 호출부가 먼저 걸러낸다(019 spec.md).
 */
public record ExitPriceInputDto(ExitPriceType exitPriceType, BigDecimal entryPrice, BigDecimal stopLoss,
	BigDecimal takeProfit, BigDecimal stopLossRate, BigDecimal takeProfitRate) {

	public ExitPriceInputDto {
		if (exitPriceType == null) {
			throw new IllegalArgumentException("exitPriceType은 필수입니다.");
		}
		if (entryPrice == null) {
			throw new IllegalArgumentException("entryPrice는 필수입니다.");
		}
		if (exitPriceType == ExitPriceType.PRICE) {
			requirePriceMode(stopLoss, takeProfit, stopLossRate, takeProfitRate);
		} else {
			requirePercentMode(stopLoss, takeProfit, stopLossRate, takeProfitRate);
		}
	}

	// PRICE 경로의 절대 가격 손절·익절 입력 snapshot
	public static ExitPriceInputDto ofPrice(BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
		return new ExitPriceInputDto(ExitPriceType.PRICE, entryPrice, stopLoss, takeProfit, null, null);
	}

	// PERCENT 경로의 손절률·익절률 입력 snapshot (`5`는 5%를 뜻한다 — 019 EXIT-PRICE-003)
	public static ExitPriceInputDto ofPercent(
		BigDecimal entryPrice, BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		return new ExitPriceInputDto(ExitPriceType.PERCENT, entryPrice, null, null, stopLossRate, takeProfitRate);
	}

	private static void requirePriceMode(
		BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		if (stopLoss == null || takeProfit == null) {
			throw new IllegalArgumentException("PRICE 방식은 stopLoss와 takeProfit이 모두 필요합니다.");
		}
		if (stopLossRate != null || takeProfitRate != null) {
			throw new IllegalArgumentException("PRICE 방식은 stopLossRate·takeProfitRate를 가질 수 없습니다.");
		}
	}

	private static void requirePercentMode(
		BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		if (stopLossRate == null || takeProfitRate == null) {
			throw new IllegalArgumentException("PERCENT 방식은 stopLossRate와 takeProfitRate가 모두 필요합니다.");
		}
		if (stopLoss != null || takeProfit != null) {
			throw new IllegalArgumentException("PERCENT 방식은 stopLoss·takeProfit을 가질 수 없습니다.");
		}
	}
}
