// LimitOrderCancelService.cancelOrder의 예약 반환·검증 순서(존재→소유→상태)를 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioSellService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LimitOrderCancelServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
	private static final Long OWNER_USER_ID = 1L;
	private static final Long OTHER_USER_ID = 2L;
	private static final Long ORDER_ID = 100L;

	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final AccountService accountService = mock(AccountService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);

	private final LimitOrderCancelService service = new LimitOrderCancelService(
		orderRepository, accountService, portfolioSellService);

	@Test
	void cancelOrderReleasesReservedCashWhenSideIsBuy() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		// quantity=0.1 * limitPrice=1,000,000 => amount=100,000, fee=floor(100,000*0.0005)=50
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.cancelOrder(OWNER_USER_ID, ORDER_ID);

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L); // 실제 현금은 불변
		verifyNoInteractions(portfolioSellService);
	}

	@Test
	void cancelOrderReleasesReservedQuantityWhenSideIsSell() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Holding holding = Holding.create(account, instrument, NOW.minusDays(1));
		holding.applyBuy(new BigDecimal("1"), new BigDecimal("900000"), NOW.minusDays(1));
		holding.reserveQuantity(new BigDecimal("0.1"));
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.SELL, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);
		when(portfolioSellService.getHoldingForUpdate(account, instrument)).thenReturn(holding);

		service.cancelOrder(OWNER_USER_ID, ORDER_ID);

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(holding.getQuantity()).isEqualByComparingTo("1"); // 실제 보유수량은 불변
		verify(portfolioSellService).getHoldingForUpdate(account, instrument);
	}

	@Test
	void cancelOrderThrowsNotFoundWhenOrderDoesNotExist() {
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancelOrder(OWNER_USER_ID, ORDER_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void cancelOrderThrowsForbiddenWhenRequesterIsNotOwner() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> service.cancelOrder(OTHER_USER_ID, ORDER_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING); // 취소되지 않는다
		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void cancelOrderThrowsOrderAlreadyFilledWhenAlreadyFilled() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.markFilled();
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> service.cancelOrder(OWNER_USER_ID, ORDER_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void cancelOrderThrowsOrderAlreadyCancelledWhenAlreadyCancelled() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.cancel();
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> service.cancelOrder(OWNER_USER_ID, ORDER_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.ORDER_ALREADY_CANCELLED);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void cancelOrderChecksOwnershipBeforeStatusSoNonOwnerOfAlreadyCancelledOrderGetsForbidden() {
		// 검증 순서(존재→소유→상태) 준수 확인: 이미 CANCELLED된 주문이라도 소유자가 아니면
		// ORDER_ALREADY_CANCELLED가 아니라 FORBIDDEN이 먼저 나와야 한다(spec.md "검증 순서" — 상태를 오류 코드로 흘리지 않음).
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.cancel();
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> service.cancelOrder(OTHER_USER_ID, ORDER_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);

		verifyNoInteractions(accountService, portfolioSellService);
	}

	@Test
	void cancelOrderDoesNotLockAccountWhenOwnershipCheckFailsBeforeStatusCheck() {
		// 검증 순서 확인 — 소유 검증 실패 시 이후 단계(account 락)를 아예 시도하지 않는다.
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(owner(), account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> service.cancelOrder(OTHER_USER_ID, ORDER_ID))
			.isInstanceOf(BusinessException.class);

		verify(accountService, never()).getAccountByIdForUpdate(any());
	}

	private static Order limitPendingOrder(
		User user, Account account, Instrument instrument, OrderSide side, String quantity, String limitPrice) {
		Order order = Order.createLimitPending(
			user, account, instrument, side, new BigDecimal(quantity), new BigDecimal(limitPrice),
			"idem-cancel-" + side, "a".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		return order;
	}

	private static Instrument cryptoInstrument() {
		Instrument instrument = Instrument.create(
			com.finplay.api.market.domain.Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		return instrument;
	}

	private static Account account() {
		Account account = Account.create(owner(), Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(account, "id", 10L);
		return account;
	}

	private static User owner() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		ReflectionTestUtils.setField(user, "id", OWNER_USER_ID);
		return user;
	}
}
