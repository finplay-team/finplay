package com.finplay.api.domain.portfolio.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HoldingValuationService {

	private static final int RETURN_RATE_SCALE = 4;

	private final PriceQueryService priceQueryService;

	private final HoldingRepository holdingRepository;

	@Transactional(readOnly = true)
	public HoldingValuationDto evaluateHolding(Holding holding) {
		PriceQuoteDto quote = priceQueryService.getPriceQuote(holding.getInstrument());
		return buildValuation(holding, quote);
	}

	@Transactional(readOnly = true)
	public List<HoldingValuationDto> evaluateHoldings(List<Holding> holdings) {
		if (holdings.isEmpty()) {
			return List.of();
		}
		List<Instrument> instruments = holdings.stream().map(Holding::getInstrument).toList();
		List<PriceQuoteDto> quotes = priceQueryService.getPriceQuotes(instruments);
		return IntStream.range(0, holdings.size())
			.mapToObj(i -> buildValuation(holdings.get(i), quotes.get(i)))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<HoldingValuationDto> evaluateActiveHoldingsForAccount(Long accountId) {
		return evaluateHoldings(holdingRepository.findAllByAccountIdAndIsActiveTrue(accountId));
	}

	private HoldingValuationDto buildValuation(Holding holding, PriceQuoteDto quote) {
		BigDecimal quantity = holding.getQuantity();
		BigDecimal averagePrice = holding.getAveragePrice();
		long costBasis = quantity.multiply(averagePrice).setScale(0, RoundingMode.FLOOR).longValueExact();

		if (quote.status() != PriceStatus.AVAILABLE) {
			return new HoldingValuationDto(quantity, averagePrice, costBasis, PriceStatus.UNAVAILABLE, null, null,
				null, null);
		}

		long evaluationAmount = quantity.multiply(quote.price()).setScale(0, RoundingMode.FLOOR).longValueExact();
		long unrealizedPnl = evaluationAmount - costBasis;
		BigDecimal returnRate = costBasis == 0
			? BigDecimal.ZERO
			: BigDecimal.valueOf(unrealizedPnl)
				.divide(BigDecimal.valueOf(costBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);

		return new HoldingValuationDto(quantity, averagePrice, costBasis, PriceStatus.AVAILABLE, quote.price(),
			evaluationAmount, unrealizedPnl, returnRate);
	}
}
