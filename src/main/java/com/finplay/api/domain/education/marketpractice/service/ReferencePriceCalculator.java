package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ReferencePriceCalculator {

	private static final int PRICE_SCALE = 8;
	private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	public Optional<ReferencePriceLines> calculate(ResolvedPracticeChainDto chain) {
		if (chain == null) {
			return Optional.empty();
		}
		return calculateFromPrice(chain.intentionStopLoss(), chain.intentionTakeProfit());
	}

	public Optional<ReferencePriceLines> calculateFromPrice(BigDecimal stopLoss, BigDecimal takeProfit) {
		if (stopLoss == null || takeProfit == null) {
			return Optional.empty();
		}
		return Optional.of(new ReferencePriceLines(
			stopLoss.setScale(PRICE_SCALE, ROUNDING_MODE), takeProfit.setScale(PRICE_SCALE, ROUNDING_MODE)));
	}

	public Optional<ReferencePriceLines> calculateFromPercent(
		BigDecimal entryPrice, BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		if (entryPrice == null || stopLossRate == null || takeProfitRate == null) {
			return Optional.empty();
		}
		BigDecimal stopLossFactor = BigDecimal.ONE.subtract(stopLossRate.divide(HUNDRED, MathContext.DECIMAL128));
		BigDecimal takeProfitFactor = BigDecimal.ONE.add(takeProfitRate.divide(HUNDRED, MathContext.DECIMAL128));
		BigDecimal referenceStopLossPrice = entryPrice.multiply(stopLossFactor).setScale(PRICE_SCALE, ROUNDING_MODE);
		BigDecimal referenceTakeProfitPrice = entryPrice.multiply(takeProfitFactor)
			.setScale(PRICE_SCALE, ROUNDING_MODE);
		return Optional.of(new ReferencePriceLines(referenceStopLossPrice, referenceTakeProfitPrice));
	}

	public BigDecimal normalizeEntryPrice(BigDecimal entryPrice) {
		return entryPrice == null ? null : entryPrice.setScale(PRICE_SCALE, ROUNDING_MODE);
	}

	public ReferencePriceLines calculateFromPreset(BigDecimal entryPrice, ExitPreset preset) {
		return calculateFromRates(entryPrice, ExitRates.of(preset == null ? ExitPreset.DEFAULT : preset));
	}

	public ReferencePriceLines calculateFromRates(BigDecimal entryPrice, ExitRates rates) {
		ExitRates applied = rates == null ? ExitRates.DEFAULT : rates;
		BigDecimal normalizedEntryPrice = normalizeEntryPrice(entryPrice);
		return calculateFromPercent(normalizedEntryPrice, applied.stopLossRate(), applied.takeProfitRate())
			.orElseThrow(() -> new IllegalArgumentException("진입 체결가 없이 손절·익절 기준선을 계산할 수 없습니다."));
	}
}
