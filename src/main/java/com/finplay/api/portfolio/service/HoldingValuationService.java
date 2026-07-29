// 보유 종목 1건의 원가·평가금액·미실현손익·수익률을 계산하는 공통 진입점 (계좌 요약·보유 종목·합산 포트폴리오 API가 재사용)
package com.finplay.api.portfolio.service;

import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
		BigDecimal quantity = holding.getQuantity();
		BigDecimal averagePrice = holding.getAveragePrice();
		long costBasis = quantity.multiply(averagePrice).setScale(0, RoundingMode.FLOOR).longValueExact();

		PriceQuoteDto quote = priceQueryService.getPriceQuote(holding.getInstrument());
		if (quote.status() == PriceStatus.UNAVAILABLE) {
			return new HoldingValuationDto(quantity, averagePrice, costBasis, PriceStatus.UNAVAILABLE, null, null,
				null);
		}

		long evaluationAmount = quantity.multiply(quote.price()).setScale(0, RoundingMode.FLOOR).longValueExact();
		long unrealizedPnl = evaluationAmount - costBasis;
		BigDecimal returnRate = costBasis == 0
			? BigDecimal.ZERO
			: BigDecimal.valueOf(unrealizedPnl)
				.divide(BigDecimal.valueOf(costBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);

		return new HoldingValuationDto(quantity, averagePrice, costBasis, PriceStatus.AVAILABLE, evaluationAmount,
			unrealizedPnl, returnRate);
	}

	@Transactional(readOnly = true)
	public List<HoldingValuationDto> evaluateActiveHoldingsForAccount(Long accountId) {
		return holdingRepository.findAllByAccountIdAndIsActiveTrue(accountId).stream()
			.map(this::evaluateHolding)
			.toList();
	}
}
