package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.entity.ExitPriceType;
import java.math.BigDecimal;

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

	public static ExitPriceInputDto ofPrice(BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
		return new ExitPriceInputDto(ExitPriceType.PRICE, entryPrice, stopLoss, takeProfit, null, null);
	}

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
