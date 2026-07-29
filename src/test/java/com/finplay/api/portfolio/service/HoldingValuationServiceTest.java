// 보유 종목 평가금액·미실현손익·수익률 계산 규칙(정상 케이스)을 검증하는 단위 테스트다.
package com.finplay.api.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.portfolio.domain.Holding;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HoldingValuationServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	private final PriceQueryService priceQueryService = Mockito.mock(PriceQueryService.class);
	private final HoldingValuationService service = new HoldingValuationService(priceQueryService);

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
