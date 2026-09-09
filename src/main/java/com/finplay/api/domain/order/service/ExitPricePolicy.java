package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class ExitPricePolicy {

	private static final int PRICE_SCALE = 8;
	private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
	private static final int MAX_INTEGER_DIGITS = 10;
	private static final int PERCENT_POINTS = 2;

	public ExitPriceLinesDto resolve(ExitPriceInputDto input) {
		ExitPriceLinesDto lines = input.exitPriceType() == ExitPriceType.PRICE ? copyFromPrice(input)
			: calculateFromPercent(input);
		validateRange(input.entryPrice(), lines);
		validatePrecision(lines);
		return lines;
	}

	private ExitPriceLinesDto copyFromPrice(ExitPriceInputDto input) {
		return new ExitPriceLinesDto(
			input.stopLoss().setScale(PRICE_SCALE, ROUNDING_MODE),
			input.takeProfit().setScale(PRICE_SCALE, ROUNDING_MODE));
	}

	private ExitPriceLinesDto calculateFromPercent(ExitPriceInputDto input) {
		BigDecimal entryPrice = input.entryPrice();
		BigDecimal normalizedStopRate = input.stopLossRate().movePointLeft(PERCENT_POINTS);
		BigDecimal normalizedTakeRate = input.takeProfitRate().movePointLeft(PERCENT_POINTS);
		return new ExitPriceLinesDto(
			entryPrice.multiply(BigDecimal.ONE.subtract(normalizedStopRate)).setScale(PRICE_SCALE, ROUNDING_MODE),
			entryPrice.multiply(BigDecimal.ONE.add(normalizedTakeRate)).setScale(PRICE_SCALE, ROUNDING_MODE));
	}

	private void validateRange(BigDecimal entryPrice, ExitPriceLinesDto lines) {
		if (lines.stopLossPrice().signum() <= 0
			|| lines.stopLossPrice().compareTo(entryPrice) >= 0
			|| lines.takeProfitPrice().compareTo(entryPrice) <= 0) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE);
		}
	}

	private void validatePrecision(ExitPriceLinesDto lines) {
		if (exceedsColumnPrecision(lines.stopLossPrice()) || exceedsColumnPrecision(lines.takeProfitPrice())) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE);
		}
	}

	private boolean exceedsColumnPrecision(BigDecimal price) {
		return price.precision() - price.scale() > MAX_INTEGER_DIGITS;
	}
}
