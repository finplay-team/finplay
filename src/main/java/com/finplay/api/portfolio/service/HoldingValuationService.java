// 보유 종목 1건의 원가·평가금액·미실현손익·수익률을 계산하는 공통 진입점 (계좌 요약·보유 종목·합산 포트폴리오 API가 재사용)
package com.finplay.api.portfolio.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
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

	// 계좌 하나의 보유 종목 여러 건을 한 번에 평가한다 — 시세는 PriceQueryService.getPriceQuotes로 배치 조회해 종목과 무관한
	// 전역 상태(재생세션·연결상태) 중복 조회를 없애고(PR #97 리뷰 권장사항), 원가·평가금액·손익 계산은 evaluateHolding과 동일한
	// buildValuation을 재사용한다. 반환 순서는 holdings 순서와 일치한다.
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

	// 보유 종목 1건의 원가·평가금액·미실현손익·수익률 계산 — evaluateHolding(단건)·evaluateHoldings(배치)가 공통으로
	// 재사용하는 private 헬퍼 (중복 코드 방지).
	private HoldingValuationDto buildValuation(Holding holding, PriceQuoteDto quote) {
		BigDecimal quantity = holding.getQuantity();
		BigDecimal averagePrice = holding.getAveragePrice();
		long costBasis = quantity.multiply(averagePrice).setScale(0, RoundingMode.FLOOR).longValueExact();

		if (quote.status() == PriceStatus.UNAVAILABLE) {
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
