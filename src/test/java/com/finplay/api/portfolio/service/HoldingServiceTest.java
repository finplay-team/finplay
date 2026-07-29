// 시장별 보유 종목 목록 조회 서비스의 매핑·경계 케이스를 검증하는 단위 테스트다.
package com.finplay.api.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class HoldingServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 0, 0);

	@Test
	void getHoldingsMapsAvailableAndUnavailablePricedHoldingsCorrectly() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		User user = User.create("user@finplay.com", "password-hash", "finplayer", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		when(accountService.getAccountFor(1L, Market.STOCK)).thenReturn(account);

		com.finplay.api.market.domain.Instrument availableInstrument = com.finplay.api.market.domain.Instrument
			.create(com.finplay.api.market.domain.Market.STOCK, "AAPL", "애플", BigDecimal.ONE, 1_000L, true, NOW);
		com.finplay.api.market.domain.Instrument unavailableInstrument = com.finplay.api.market.domain.Instrument
			.create(com.finplay.api.market.domain.Market.STOCK, "TSLA", "테슬라", BigDecimal.ONE, 1_000L, true, NOW);
		Holding availableHolding = Holding.create(account, availableInstrument, NOW);
		availableHolding.applyBuy(BigDecimal.TEN, BigDecimal.valueOf(1_000), NOW);
		Holding unavailableHolding = Holding.create(account, unavailableInstrument, NOW);
		unavailableHolding.applyBuy(BigDecimal.ONE, BigDecimal.valueOf(500_000), NOW);

		when(holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId()))
			.thenReturn(List.of(availableHolding, unavailableHolding));

		HoldingValuationDto availableValuation = new HoldingValuationDto(
			BigDecimal.TEN, BigDecimal.valueOf(1_000), 10_000L, PriceStatus.AVAILABLE, BigDecimal.valueOf(1_200),
			12_000L, 2_000L, BigDecimal.valueOf(0.2000));
		HoldingValuationDto unavailableValuation = new HoldingValuationDto(
			BigDecimal.ONE, BigDecimal.valueOf(500_000), 500_000L, PriceStatus.UNAVAILABLE, null, null, null, null);
		when(holdingValuationService.evaluateHolding(availableHolding)).thenReturn(availableValuation);
		when(holdingValuationService.evaluateHolding(unavailableHolding)).thenReturn(unavailableValuation);

		List<HoldingListItemResponse> result = holdingService.getHoldings(1L, Market.STOCK);

		assertThat(result).hasSize(2);

		HoldingListItemResponse available = result.get(0);
		assertThat(available.instrumentId()).isEqualTo(availableInstrument.getId());
		assertThat(available.symbol()).isEqualTo("AAPL");
		assertThat(available.name()).isEqualTo("애플");
		assertThat(available.quantity()).isEqualByComparingTo(BigDecimal.TEN);
		assertThat(available.averagePrice()).isEqualByComparingTo(BigDecimal.valueOf(1_000));
		assertThat(available.currentPrice()).isEqualByComparingTo(BigDecimal.valueOf(1_200));
		assertThat(available.evaluationAmount()).isEqualTo(12_000L);
		assertThat(available.unrealizedPnl()).isEqualTo(2_000L);
		assertThat(available.returnRate()).isEqualByComparingTo(BigDecimal.valueOf(0.2000));
		assertThat(available.priceStatus()).isEqualTo("AVAILABLE");

		HoldingListItemResponse unavailable = result.get(1);
		assertThat(unavailable.symbol()).isEqualTo("TSLA");
		assertThat(unavailable.currentPrice()).isNull();
		assertThat(unavailable.evaluationAmount()).isNull();
		assertThat(unavailable.unrealizedPnl()).isNull();
		assertThat(unavailable.returnRate()).isNull();
		assertThat(unavailable.priceStatus()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void getHoldingsReturnsEmptyListWhenNoActiveHoldings() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		User user = User.create("user@finplay.com", "password-hash", "finplayer", NOW);
		Account account = Account.create(user, Market.CRYPTO, NOW);
		when(accountService.getAccountFor(1L, Market.CRYPTO)).thenReturn(account);
		when(holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId())).thenReturn(List.of());

		List<HoldingListItemResponse> result = holdingService.getHoldings(1L, Market.CRYPTO);

		assertThat(result).isEmpty();
	}

	@Test
	void getHoldingsThrowsNotFoundWhenNoAccountExistsForUserAndMarket() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		when(accountService.getAccountFor(1L, Market.STOCK))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> holdingService.getHoldings(1L, Market.STOCK))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}
}
