// 보유 종목 평가금액·미실현손익·수익률 계산 규칙(정상 케이스)을 검증하는 단위 테스트다.
package com.finplay.api.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HoldingValuationServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	private final PriceQueryService priceQueryService = Mockito.mock(PriceQueryService.class);
	private final HoldingRepository holdingRepository = Mockito.mock(HoldingRepository.class);
	private final HoldingValuationService service = new HoldingValuationService(priceQueryService, holdingRepository);

	@Test
	void evaluateHoldingReturnsPositivePnlAndReturnRateWhenPriceRoseAboveAveragePrice() {
		Instrument instrument = testInstrument();
		Holding holding = testHolding(instrument, new BigDecimal("10"), new BigDecimal("50000"));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(new BigDecimal("60000"), NOW, PriceStatus.AVAILABLE, null));

		HoldingValuationDto result = service.evaluateHolding(holding);

		assertThat(result.quantity()).isEqualByComparingTo("10");
		assertThat(result.averagePrice()).isEqualByComparingTo("50000");
		assertThat(result.costBasis()).isEqualTo(500_000L);
		assertThat(result.priceStatus()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.evaluationAmount()).isEqualTo(600_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(100_000L);
		assertThat(result.returnRate()).isEqualByComparingTo("0.2000");
		assertThat(result.currentPrice()).isEqualByComparingTo("60000");
	}

	@Test
	void evaluateHoldingReturnsNegativePnlAndReturnRateWhenPriceFellBelowAveragePrice() {
		Instrument instrument = testInstrument();
		Holding holding = testHolding(instrument, new BigDecimal("10"), new BigDecimal("50000"));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(new BigDecimal("40000"), NOW, PriceStatus.AVAILABLE, null));

		HoldingValuationDto result = service.evaluateHolding(holding);

		assertThat(result.costBasis()).isEqualTo(500_000L);
		assertThat(result.priceStatus()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.evaluationAmount()).isEqualTo(400_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(-100_000L);
		assertThat(result.returnRate()).isEqualByComparingTo("-0.2000");
		assertThat(result.currentPrice()).isEqualByComparingTo("40000");
	}

	@Test
	void evaluateHoldingReturnsNullEvaluationFieldsWithoutThrowingWhenPriceUnavailable() {
		Instrument instrument = testInstrument();
		Holding holding = testHolding(instrument, new BigDecimal("10"), new BigDecimal("50000"));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));

		HoldingValuationDto result = service.evaluateHolding(holding);

		assertThat(result.costBasis()).isEqualTo(500_000L);
		assertThat(result.priceStatus()).isEqualTo(PriceStatus.UNAVAILABLE);
		assertThat(result.evaluationAmount()).isNull();
		assertThat(result.unrealizedPnl()).isNull();
		assertThat(result.returnRate()).isNull();
		assertThat(result.currentPrice()).isNull();
	}

	@Test
	void evaluateHoldingReturnsUnavailableStatusAndNullEvaluationFieldsWhenPriceStale() {
		// PRICE-STALE-005 엄격 유지 고정 — STALE도 UNAVAILABLE과 동일하게 평가불가 처리해야 한다.
		Instrument instrument = testInstrument();
		Holding holding = testHolding(instrument, new BigDecimal("10"), new BigDecimal("50000"));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(new BigDecimal("60000"), NOW, PriceStatus.STALE, null));

		HoldingValuationDto result = service.evaluateHolding(holding);

		assertThat(result.costBasis()).isEqualTo(500_000L);
		assertThat(result.priceStatus()).isEqualTo(PriceStatus.UNAVAILABLE);
		assertThat(result.evaluationAmount()).isNull();
		assertThat(result.unrealizedPnl()).isNull();
		assertThat(result.returnRate()).isNull();
		assertThat(result.currentPrice()).isNull();
	}

	@Test
	void evaluateHoldingReturnsZeroCostBasisAndZeroReturnRateWhenQuantityIsZero() {
		Instrument instrument = testInstrument();
		Account account = testAccount();
		Holding holding = Holding.create(account, instrument, NOW);
		holding.applyBuy(new BigDecimal("10"), new BigDecimal("50000"), NOW);
		holding.applySell(new BigDecimal("10"), NOW);
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(new BigDecimal("60000"), NOW, PriceStatus.AVAILABLE, null));

		HoldingValuationDto result = service.evaluateHolding(holding);

		assertThat(result.quantity()).isEqualByComparingTo("0");
		assertThat(result.costBasis()).isEqualTo(0L);
		assertThat(result.priceStatus()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.evaluationAmount()).isEqualTo(0L);
		assertThat(result.unrealizedPnl()).isEqualTo(0L);
		assertThat(result.returnRate()).isEqualByComparingTo("0");
	}

	@Test
	void evaluateHoldingReturnsZeroCostBasisAndReturnRateWhenAveragePriceIsZero() {
		Instrument instrument = testInstrument();
		Account account = testAccount();
		Holding holding = Holding.create(account, instrument, NOW);
		holding.applyBuy(new BigDecimal("10"), BigDecimal.ZERO, NOW);
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(new BigDecimal("60000"), NOW, PriceStatus.AVAILABLE, null));

		HoldingValuationDto result = service.evaluateHolding(holding);

		assertThat(result.averagePrice()).isEqualByComparingTo("0");
		assertThat(result.costBasis()).isEqualTo(0L);
		assertThat(result.priceStatus()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.evaluationAmount()).isEqualTo(600_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(600_000L);
		assertThat(result.returnRate()).isEqualByComparingTo("0");
	}

	// 이하 evaluateHoldings(List) — 배치 평가 (PR #97 리뷰 권장사항).

	@Test
	void evaluateHoldingsCallsPriceQueryServiceBatchMethodOnceInsteadOfPerHolding() {
		Instrument firstInstrument = testInstrument();
		Instrument secondInstrument = Instrument.create(
			com.finplay.api.market.domain.Market.STOCK, "000660", "SK하이닉스", new BigDecimal("100"), 0L, true, NOW);
		Holding firstHolding = testHolding(firstInstrument, new BigDecimal("10"), new BigDecimal("50000"));
		Holding secondHolding = testHolding(secondInstrument, new BigDecimal("5"), new BigDecimal("100000"));
		when(priceQueryService.getPriceQuotes(List.of(firstInstrument, secondInstrument)))
			.thenReturn(List.of(
				new PriceQuoteDto(new BigDecimal("60000"), NOW, PriceStatus.AVAILABLE, null),
				new PriceQuoteDto(new BigDecimal("90000"), NOW, PriceStatus.AVAILABLE, null)));

		List<HoldingValuationDto> result = service.evaluateHoldings(List.of(firstHolding, secondHolding));

		assertThat(result).hasSize(2);
		assertThat(result.get(0).evaluationAmount()).isEqualTo(600_000L);
		assertThat(result.get(1).evaluationAmount()).isEqualTo(450_000L);
		// 종목 수(2건)와 무관하게 시세 배치 조회는 요청당 1회만 일어나야 한다(종목별 getPriceQuote 반복 호출 금지).
		verify(priceQueryService, times(1)).getPriceQuotes(any());
		verify(priceQueryService, never()).getPriceQuote(any(Instrument.class));
	}

	@Test
	void evaluateHoldingsProducesSameResultsAsCallingEvaluateHoldingIndividually() {
		// 회귀 확인 — 배치 경로(evaluateHoldings)와 건별 경로(evaluateHolding)는 같은 시세 입력에 대해
		// 완전히 동일한 원가·평가금액·손익·수익률을 계산해야 한다(성능 리팩터링이라 계산 결과가 달라지면 안 됨).
		Instrument firstInstrument = testInstrument();
		Instrument secondInstrument = Instrument.create(
			com.finplay.api.market.domain.Market.CRYPTO, "BTC", "비트코인", new BigDecimal("100"), 0L, true, NOW);
		Holding firstHolding = testHolding(firstInstrument, new BigDecimal("10"), new BigDecimal("50000"));
		Holding secondHolding = testHolding(secondInstrument, new BigDecimal("3"), new BigDecimal("200"));
		PriceQuoteDto firstQuote = new PriceQuoteDto(new BigDecimal("60000"), NOW, PriceStatus.AVAILABLE, null);
		PriceQuoteDto secondQuote = new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null);
		when(priceQueryService.getPriceQuotes(List.of(firstInstrument, secondInstrument)))
			.thenReturn(List.of(firstQuote, secondQuote));
		when(priceQueryService.getPriceQuote(firstInstrument)).thenReturn(firstQuote);
		when(priceQueryService.getPriceQuote(secondInstrument)).thenReturn(secondQuote);

		List<HoldingValuationDto> batchResult = service.evaluateHoldings(List.of(firstHolding, secondHolding));
		HoldingValuationDto individualFirst = service.evaluateHolding(firstHolding);
		HoldingValuationDto individualSecond = service.evaluateHolding(secondHolding);

		assertThat(batchResult.get(0)).isEqualTo(individualFirst);
		assertThat(batchResult.get(1)).isEqualTo(individualSecond);
	}

	@Test
	void evaluateHoldingsReturnsEmptyListWithoutQueryingPricesWhenHoldingsIsEmpty() {
		List<HoldingValuationDto> result = service.evaluateHoldings(List.of());

		assertThat(result).isEmpty();
		verify(priceQueryService, never()).getPriceQuotes(any());
	}

	@Test
	void evaluateActiveHoldingsForAccountReturnsEvaluatedListForMixedPriceAvailability() {
		Long accountId = 1L;
		Instrument availableInstrument = testInstrument();
		Instrument unavailableInstrument = Instrument.create(
			com.finplay.api.market.domain.Market.CRYPTO,
			"BTC",
			"비트코인",
			new BigDecimal("100"),
			0L,
			true,
			NOW);
		Holding availableHolding = testHolding(availableInstrument, new BigDecimal("10"), new BigDecimal("50000"));
		Holding unavailableHolding = testHolding(unavailableInstrument, new BigDecimal("5"), new BigDecimal("100"));
		when(holdingRepository.findAllByAccountIdAndIsActiveTrue(accountId))
			.thenReturn(List.of(availableHolding, unavailableHolding));
		// evaluateActiveHoldingsForAccount -> evaluateHoldings는 배치 경로(getPriceQuotes)를 사용한다
		// (PR #97 리뷰 권장사항 배치화 — 종목별 getPriceQuote가 아닌 리스트 단위 조회로 위임).
		when(priceQueryService.getPriceQuotes(List.of(availableInstrument, unavailableInstrument)))
			.thenReturn(List.of(
				new PriceQuoteDto(new BigDecimal("60000"), NOW, PriceStatus.AVAILABLE, null),
				new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null)));

		List<HoldingValuationDto> result = service.evaluateActiveHoldingsForAccount(accountId);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).priceStatus()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.get(0).evaluationAmount()).isEqualTo(600_000L);
		assertThat(result.get(0).unrealizedPnl()).isEqualTo(100_000L);
		assertThat(result.get(1).priceStatus()).isEqualTo(PriceStatus.UNAVAILABLE);
		assertThat(result.get(1).evaluationAmount()).isNull();
		assertThat(result.get(1).unrealizedPnl()).isNull();
	}

	@Test
	void evaluateActiveHoldingsForAccountReturnsEmptyListWhenNoActiveHoldings() {
		Long accountId = 2L;
		when(holdingRepository.findAllByAccountIdAndIsActiveTrue(accountId)).thenReturn(List.of());

		List<HoldingValuationDto> result = service.evaluateActiveHoldingsForAccount(accountId);

		assertThat(result).isEmpty();
	}

	private static Holding testHolding(Instrument instrument, BigDecimal quantity, BigDecimal price) {
		Account account = testAccount();
		Holding holding = Holding.create(account, instrument, NOW);
		holding.applyBuy(quantity, price, NOW);
		return holding;
	}

	private static Account testAccount() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		return Account.create(user, com.finplay.api.account.domain.Market.STOCK, NOW);
	}

	private static Instrument testInstrument() {
		return Instrument.create(
			com.finplay.api.market.domain.Market.STOCK,
			"005930",
			"삼성전자",
			new BigDecimal("100"),
			0L,
			true,
			NOW);
	}
}
