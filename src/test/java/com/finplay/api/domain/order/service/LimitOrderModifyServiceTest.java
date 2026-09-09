package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.request.LimitOrderUpdateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class LimitOrderModifyServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
	private static final Long OWNER_USER_ID = 1L;
	private static final Long OTHER_USER_ID = 2L;
	private static final Long ORDER_ID = 100L;
	private static final Long ACCOUNT_ID = 10L;

	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final AccountService accountService = mock(AccountService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

	private final LimitOrderModifyService service = new LimitOrderModifyService(
		orderRepository, accountService, tutorialAccountService, portfolioSellService, clock);

	@Test
	void modifyOrderBuyReleasesOldReservationThenReservesNewReservationInOrder() {
		Instrument instrument = cryptoInstrument();
		Account account = mock(Account.class);
		when(account.getId()).thenReturn(ACCOUNT_ID);
		when(account.getAvailableCash()).thenReturn(Long.MAX_VALUE);
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("2000000"), new BigDecimal("0.2"));

		service.modifyOrder(OWNER_USER_ID, ORDER_ID, request);

		InOrder inOrder = inOrder(account);
		inOrder.verify(account).releaseReservedCash(100_050L);
		inOrder.verify(account).reserveCash(400_200L);
		verifyNoInteractions(portfolioSellService);
	}

	@Test
	void modifyOrderBuyForTutorialSampleReleasesOldReservationThenReservesNewInTutorialAccountOnly() {
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		TutorialAccount tutorialAccount = tutorialAccount();
		tutorialAccount.reserveCash(100_050L);
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		when(tutorialAccountService.getOrCreateForUpdate(OWNER_USER_ID, Market.CRYPTO, NOW))
			.thenReturn(tutorialAccount);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("2000000"), new BigDecimal("0.2"));

		service.modifyOrder(OWNER_USER_ID, ORDER_ID, request);

		assertThat(tutorialAccount.getReservedCash()).isEqualTo(400_200L);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(order.getQuantity()).isEqualByComparingTo("0.2");
		assertThat(order.getLimitPrice()).isEqualByComparingTo("2000000");
	}

	@Test
	void modifyOrderBuyForTutorialSampleThrowsTutorialInsufficientCashRegardlessOfRealAccountBalanceAndNeverReReserves() {
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		account.addCash(100_000_000L);
		TutorialAccount tutorialAccount = tutorialAccount();
		tutorialAccount.reserveCash(100_050L);
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		when(tutorialAccountService.getOrCreateForUpdate(OWNER_USER_ID, Market.CRYPTO, NOW))
			.thenReturn(tutorialAccount);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("15000000"), new BigDecimal("1"));

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TUTORIAL_INSUFFICIENT_CASH);

		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(110_000_000L);
	}

	@Test
	void modifyOrderSellReleasesOldReservedQuantityThenReservesNewQuantityInOrder() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Holding holding = mock(Holding.class);
		when(holding.getAvailableQuantity()).thenReturn(new BigDecimal("100"));
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.SELL, "1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		when(portfolioSellService.getHoldingForUpdate(account, instrument)).thenReturn(holding);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("1200000"), new BigDecimal("0.5"));

		service.modifyOrder(OWNER_USER_ID, ORDER_ID, request);

		InOrder inOrder = inOrder(holding);
		inOrder.verify(holding).releaseReservedQuantity(new BigDecimal("1"));
		inOrder.verify(holding).reserveQuantity(new BigDecimal("0.5"));
	}

	@Test
	void modifyOrderKeepsExistingQuantityWhenOnlyLimitPriceProvidedForBuy() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("2000000"), null);

		LimitOrderResponse response = service.modifyOrder(OWNER_USER_ID, ORDER_ID, request);

		assertThat(order.getQuantity()).isEqualByComparingTo("0.1");
		assertThat(order.getLimitPrice()).isEqualByComparingTo("2000000");
		assertThat(account.getReservedCash()).isEqualTo(200_100L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(response.status()).isEqualTo("PENDING");
		assertThat(response.quantity()).isEqualByComparingTo("0.1");
		assertThat(response.limitPrice()).isEqualByComparingTo("2000000");
		assertThat(response.requestedAt()).isEqualTo(NOW);
	}

	@Test
	void modifyOrderKeepsExistingLimitPriceWhenOnlyQuantityProvidedForBuy() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(null, new BigDecimal("0.3"));

		LimitOrderResponse response = service.modifyOrder(OWNER_USER_ID, ORDER_ID, request);

		assertThat(order.getQuantity()).isEqualByComparingTo("0.3");
		assertThat(order.getLimitPrice()).isEqualByComparingTo("1000000");
		assertThat(account.getReservedCash()).isEqualTo(300_150L);
		assertThat(response.limitPrice()).isEqualByComparingTo("1000000");
		assertThat(response.quantity()).isEqualByComparingTo("0.3");
	}

	@Test
	void modifyOrderKeepsExistingLimitPriceWhenOnlyQuantityProvidedForSell() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Holding holding = Holding.create(account, instrument, NOW.minusDays(1));
		holding.applyBuy(new BigDecimal("2"), new BigDecimal("900000"), NOW.minusDays(1));
		holding.reserveQuantity(new BigDecimal("1"));
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.SELL, "1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		when(portfolioSellService.getHoldingForUpdate(account, instrument)).thenReturn(holding);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(null, new BigDecimal("0.4"));

		LimitOrderResponse response = service.modifyOrder(OWNER_USER_ID, ORDER_ID, request);

		assertThat(order.getQuantity()).isEqualByComparingTo("0.4");
		assertThat(order.getLimitPrice()).isEqualByComparingTo("1000000");
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo("0.4");
		assertThat(holding.getQuantity()).isEqualByComparingTo("2");
		assertThat(response.quantity()).isEqualByComparingTo("0.4");
	}

	@Test
	void modifyOrderThrowsValidationErrorWhenBothQuantityAndLimitPriceAreNull() {
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(null, null);

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verifyNoInteractions(orderRepository, accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsNotFoundWhenOrderDoesNotExist() {
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("1000000"), null);

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsForbiddenWhenRequesterIsNotOwner() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("2000000"), null);

		assertThatThrownBy(() -> service.modifyOrder(OTHER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);

		assertThat(order.getLimitPrice()).isEqualByComparingTo("1000000");
		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsOrderAlreadyFilledWhenAlreadyFilled() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.markFilled();
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("2000000"), null);

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsOrderAlreadyCancelledWhenAlreadyCancelled() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.cancel();
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("2000000"), null);

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.ORDER_ALREADY_CANCELLED);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderChecksOwnershipBeforeStatusSoNonOwnerOfAlreadyCancelledOrderGetsForbidden() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.cancel();
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("2000000"), null);

		assertThatThrownBy(() -> service.modifyOrder(OTHER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderChecksStatusBeforeFormatSoAlreadyFilledOrderWithInvalidQuantityStillGetsOrderAlreadyFilled() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.markFilled();
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(null, new BigDecimal("-1"));

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsValidationErrorWhenFinalQuantityIsZero() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(null, BigDecimal.ZERO);

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsValidationErrorWhenFinalQuantityScaleExceedsEightDecimals() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(null, new BigDecimal("0.123456789"));

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsValidationErrorWhenFinalLimitPriceIsZero() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(BigDecimal.ZERO, null);

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsValidationErrorWhenFinalAmountBelowMinOrderAmount() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("1000"), new BigDecimal("0.001"));

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void modifyOrderThrowsInsufficientCashWhenNewReservationExceedsAvailableCashAndNeverReserves() {
		Instrument instrument = cryptoInstrument();
		Account account = mock(Account.class);
		when(account.getId()).thenReturn(ACCOUNT_ID);
		when(account.getAvailableCash()).thenReturn(0L);
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("2000000"), new BigDecimal("0.2"));

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.INSUFFICIENT_CASH);

		verify(account).releaseReservedCash(100_050L);
		verify(account, never()).reserveCash(anyLong());
	}

	@Test
	void modifyOrderThrowsInsufficientQtyWhenNewReservationExceedsAvailableQuantityAndNeverReserves() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Holding holding = mock(Holding.class);
		when(holding.getAvailableQuantity()).thenReturn(BigDecimal.ZERO);
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.SELL, "1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(ACCOUNT_ID)).thenReturn(account);
		when(portfolioSellService.getHoldingForUpdate(account, instrument)).thenReturn(holding);
		LimitOrderUpdateRequest request = new LimitOrderUpdateRequest(new BigDecimal("1200000"), new BigDecimal("0.5"));

		assertThatThrownBy(() -> service.modifyOrder(OWNER_USER_ID, ORDER_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.INSUFFICIENT_QTY);

		verify(holding).releaseReservedQuantity(new BigDecimal("1"));
		verify(holding, never()).reserveQuantity(org.mockito.ArgumentMatchers.any());
	}

	private static Order limitPendingOrder(
		User user, Account account, Instrument instrument, OrderSide side, String quantity, String limitPrice) {
		Order order = Order.createLimitPending(
			user, account, instrument, side, new BigDecimal(quantity), new BigDecimal(limitPrice),
			"idem-modify-" + side, "a".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		return order;
	}

	private static Instrument cryptoInstrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true,
			NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		return instrument;
	}

	private static Account account() {
		Account account = Account.create(owner(), Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
		return account;
	}

	private static TutorialAccount tutorialAccount() {
		return TutorialAccount.create(owner(), Market.CRYPTO, NOW);
	}

	private static User owner() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		ReflectionTestUtils.setField(user, "id", OWNER_USER_ID);
		return user;
	}
}
