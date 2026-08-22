// 시장별 보유 종목 목록 조회 서비스의 매핑·경계 케이스를 검증하는 단위 테스트다.
package com.finplay.api.domain.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

		com.finplay.api.domain.market.entity.Instrument availableInstrument = com.finplay.api.domain.market.entity.Instrument
			.create(Market.STOCK, "AAPL", "애플", BigDecimal.ONE, 1_000L, true, NOW);
		com.finplay.api.domain.market.entity.Instrument unavailableInstrument = com.finplay.api.domain.market.entity.Instrument
			.create(Market.STOCK, "TSLA", "테슬라", BigDecimal.ONE, 1_000L, true,
				NOW);
		Holding availableHolding = Holding.create(account, availableInstrument, NOW);
		availableHolding.applyBuy(BigDecimal.TEN, BigDecimal.valueOf(1_000), NOW);
		availableHolding.reserveQuantity(BigDecimal.valueOf(4));
		org.springframework.test.util.ReflectionTestUtils.setField(availableHolding, "id", 101L);
		Holding unavailableHolding = Holding.create(account, unavailableInstrument, NOW);
		unavailableHolding.applyBuy(BigDecimal.ONE, BigDecimal.valueOf(500_000), NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(unavailableHolding, "id", 102L);

		when(holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId()))
			.thenReturn(List.of(availableHolding, unavailableHolding));

		HoldingValuationDto availableValuation = new HoldingValuationDto(
			BigDecimal.TEN, BigDecimal.valueOf(1_000), 10_000L, PriceStatus.AVAILABLE, BigDecimal.valueOf(1_200),
			12_000L, 2_000L, BigDecimal.valueOf(0.2000));
		HoldingValuationDto unavailableValuation = new HoldingValuationDto(
			BigDecimal.ONE, BigDecimal.valueOf(500_000), 500_000L, PriceStatus.UNAVAILABLE, null, null, null, null);
		// HoldingService.getHoldings는 배치 경로(evaluateHoldings)를 사용한다 (PR #97 리뷰 권장사항 배치화).
		when(holdingValuationService.evaluateHoldings(List.of(availableHolding, unavailableHolding)))
			.thenReturn(List.of(availableValuation, unavailableValuation));

		List<HoldingListItemResponse> result = holdingService.getHoldings(1L, Market.STOCK);

		assertThat(result).hasSize(2);

		HoldingListItemResponse available = result.get(0);
		assertThat(available.holdingId()).isEqualTo(availableHolding.getId());
		assertThat(available.instrumentId()).isEqualTo(availableInstrument.getId());
		assertThat(available.symbol()).isEqualTo("AAPL");
		assertThat(available.name()).isEqualTo("애플");
		assertThat(available.quantity()).isEqualByComparingTo(BigDecimal.TEN);
		assertThat(available.reservedQuantity()).isEqualByComparingTo(BigDecimal.valueOf(4));
		assertThat(available.averagePrice()).isEqualByComparingTo(BigDecimal.valueOf(1_000));
		assertThat(available.currentPrice()).isEqualByComparingTo(BigDecimal.valueOf(1_200));
		assertThat(available.evaluationAmount()).isEqualTo(12_000L);
		assertThat(available.unrealizedPnl()).isEqualTo(2_000L);
		assertThat(available.returnRate()).isEqualByComparingTo(BigDecimal.valueOf(0.2000));
		assertThat(available.priceStatus()).isEqualTo("AVAILABLE");

		HoldingListItemResponse unavailable = result.get(1);
		assertThat(unavailable.holdingId()).isEqualTo(unavailableHolding.getId());
		assertThat(unavailable.symbol()).isEqualTo("TSLA");
		assertThat(unavailable.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
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

	// 아래는 026-market-order-practice-tutorial 2단계 chain 해석이 쓰는 findHoldingId(owner·instrument 일치
	// holding id만 반환, market.domain.Market -> account.domain.Market 변환)를 검증한다.

	@Test
	void findHoldingIdReturnsHoldingIdWhenOwnerAndInstrumentMatch() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		User user = User.create("user@finplay.com", "password-hash", "finplayer", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(1L, Market.STOCK)).thenReturn(account);

		com.finplay.api.domain.market.entity.Instrument instrument = com.finplay.api.domain.market.entity.Instrument
			.create(Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 0L, true, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(holding, "id", 99L);

		when(holdingRepository.findByAccountIdAndInstrumentId(10L, 100L)).thenReturn(java.util.Optional.of(holding));

		java.util.Optional<Long> result = holdingService.findHoldingId(
			1L, Market.STOCK, 100L);

		assertThat(result).contains(99L);
	}

	@Test
	void findHoldingIdReturnsEmptyWhenNoHoldingExistsForAccountAndInstrument() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		User user = User.create("user@finplay.com", "password-hash", "finplayer", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(1L, Market.STOCK)).thenReturn(account);
		when(holdingRepository.findByAccountIdAndInstrumentId(10L, 100L)).thenReturn(java.util.Optional.empty());

		java.util.Optional<Long> result = holdingService.findHoldingId(
			1L, Market.STOCK, 100L);

		assertThat(result).isEmpty();
	}

	@Test
	void findHoldingIdConvertsMarketDomainMarketToAccountDomainMarketForCryptoAccountLookup() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		User user = User.create("user@finplay.com", "password-hash", "finplayer", NOW);
		Account account = Account.create(user, Market.CRYPTO, NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(account, "id", 20L);
		when(accountService.getAccountFor(1L, Market.CRYPTO)).thenReturn(account);
		when(holdingRepository.findByAccountIdAndInstrumentId(20L, 200L)).thenReturn(java.util.Optional.empty());

		holdingService.findHoldingId(1L, Market.CRYPTO, 200L);

		org.mockito.Mockito.verify(accountService).getAccountFor(1L, Market.CRYPTO);
		org.mockito.Mockito.verify(accountService, org.mockito.Mockito.never()).getAccountFor(1L, Market.STOCK);
	}

	// 아래는 026-market-order-practice-tutorial 3단계 관찰 API가 쓰는 findHoldingForOwner(holdingId로 조회하되
	// 계좌 소유자가 본인이 아니면 존재를 숨겨 빈 값을 반환)를 검증한다.

	@Test
	void findHoldingForOwnerReturnsHoldingWhenOwnerMatches() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		User owner = User.create("owner@finplay.com", "password-hash", "owner", NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(owner, "id", 1L);
		Account account = Account.create(owner, Market.STOCK, NOW);
		com.finplay.api.domain.market.entity.Instrument instrument = com.finplay.api.domain.market.entity.Instrument
			.create(Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 0L, true, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(holding, "id", 99L);

		when(holdingRepository.findByIdFetchingInstrument(99L)).thenReturn(java.util.Optional.of(holding));

		java.util.Optional<Holding> result = holdingService.findHoldingForOwner(1L, 99L);

		assertThat(result).contains(holding);
	}

	@Test
	void findHoldingForOwnerReturnsEmptyWhenHoldingDoesNotExist() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		when(holdingRepository.findByIdFetchingInstrument(99L)).thenReturn(java.util.Optional.empty());

		java.util.Optional<Holding> result = holdingService.findHoldingForOwner(1L, 99L);

		assertThat(result).isEmpty();
	}

	@Test
	void findHoldingForOwnerReturnsEmptyWhenHoldingBelongsToAnotherUser() {
		AccountService accountService = mock(AccountService.class);
		HoldingRepository holdingRepository = mock(HoldingRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		HoldingService holdingService = new HoldingService(accountService, holdingRepository,
			holdingValuationService);

		User otherOwner = User.create("other@finplay.com", "password-hash", "other", NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(otherOwner, "id", 2L);
		Account account = Account.create(otherOwner, Market.STOCK, NOW);
		com.finplay.api.domain.market.entity.Instrument instrument = com.finplay.api.domain.market.entity.Instrument
			.create(Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 0L, true, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(holding, "id", 99L);

		when(holdingRepository.findByIdFetchingInstrument(99L)).thenReturn(java.util.Optional.of(holding));

		java.util.Optional<Holding> result = holdingService.findHoldingForOwner(1L, 99L);

		assertThat(result).isEmpty();
	}
}
