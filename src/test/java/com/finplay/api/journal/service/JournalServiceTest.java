// JournalService.createBuyJournal의 검증 순서·저장 인자·예외 변환을 검증하는 단위 테스트다.
package com.finplay.api.journal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.journal.domain.BuyTradeJournal;
import com.finplay.api.journal.dto.response.BuyJournalResponse;
import com.finplay.api.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class JournalServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long BUY_TRADE_ID = 5L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-04T10:00:00Z");
	private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final TradeService tradeService = mock(TradeService.class);
	private final BuyTradeJournalRepository buyTradeJournalRepository = mock(BuyTradeJournalRepository.class);

	private final JournalService journalService = new JournalService(tradeService, buyTradeJournalRepository,
		FIXED_CLOCK);

	@Test
	void createBuyJournalSavesJournalWithFixedClockAndReturnsAllFields() {
		Trade trade = buyTrade(BUY_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenReturn(trade);
		when(buyTradeJournalRepository.existsByBuyTradeId(BUY_TRADE_ID)).thenReturn(false);
		when(buyTradeJournalRepository.saveAndFlush(any(BuyTradeJournal.class))).thenAnswer(invocation -> {
			BuyTradeJournal saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 100L);
			return saved;
		});

		BuyJournalResponse response = journalService.createBuyJournal(USER_ID, BUY_TRADE_ID, "분할 매수 계획");

		ArgumentCaptor<BuyTradeJournal> captor = ArgumentCaptor.forClass(BuyTradeJournal.class);
		verify(buyTradeJournalRepository).saveAndFlush(captor.capture());
		BuyTradeJournal savedArg = captor.getValue();
		assertThat(savedArg.getBuyTrade()).isSameAs(trade);
		assertThat(savedArg.getContent()).isEqualTo("분할 매수 계획");
		assertThat(savedArg.getCreatedAt()).isEqualTo(NOW);

		assertThat(response.journalId()).isEqualTo(100L);
		assertThat(response.buyTradeId()).isEqualTo(BUY_TRADE_ID);
		assertThat(response.content()).isEqualTo("분할 매수 계획");
		assertThat(response.createdAt()).isEqualTo(NOW);
	}

	@Test
	void createBuyJournalPropagatesNotFoundWhenTradeDoesNotExist() {
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> journalService.createBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verify(buyTradeJournalRepository, never()).existsByBuyTradeId(any());
		verify(buyTradeJournalRepository, never()).saveAndFlush(any());
	}

	@Test
	void createBuyJournalPropagatesForbiddenWhenTradeOwnedByAnotherUser() {
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> journalService.createBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));

		verify(buyTradeJournalRepository, never()).existsByBuyTradeId(any());
		verify(buyTradeJournalRepository, never()).saveAndFlush(any());
	}

	@Test
	void createBuyJournalThrowsValidationErrorWhenTradeIsNotBuySide() {
		Trade sellTrade = sellTrade(BUY_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenReturn(sellTrade);

		assertThatThrownBy(() -> journalService.createBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(buyTradeJournalRepository, never()).existsByBuyTradeId(any());
		verify(buyTradeJournalRepository, never()).saveAndFlush(any());
	}

	@Test
	void createBuyJournalThrowsDuplicateResourceWhenJournalAlreadyExists() {
		Trade trade = buyTrade(BUY_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenReturn(trade);
		when(buyTradeJournalRepository.existsByBuyTradeId(BUY_TRADE_ID)).thenReturn(true);

		assertThatThrownBy(() -> journalService.createBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.DUPLICATE_RESOURCE));

		verify(buyTradeJournalRepository, never()).saveAndFlush(any());
	}

	// 검증 순서 확인 — 타인 소유의 매도 체결은 getOwnedTrade 단계에서 403으로 끝나야 한다.
	// side 검사(400)까지 도달하면 이 테스트가 실패해 검증 순서 위반을 드러낸다.
	@Test
	void createBuyJournalReturnsForbiddenNotValidationErrorForOtherUsersSellTrade() {
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> journalService.createBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN)
				.isNotEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void createBuyJournalConvertsDataIntegrityViolationToDuplicateResource() {
		Trade trade = buyTrade(BUY_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenReturn(trade);
		when(buyTradeJournalRepository.existsByBuyTradeId(BUY_TRADE_ID)).thenReturn(false);
		when(buyTradeJournalRepository.saveAndFlush(any(BuyTradeJournal.class)))
			.thenThrow(new DataIntegrityViolationException("duplicate key"));

		assertThatThrownBy(() -> journalService.createBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
	}

	private static Trade buyTrade(Long id) {
		Order order = order();
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, NOW.minusMinutes(1), NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	private static Trade sellTrade(Long id) {
		Order order = order();
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), OrderSide.SELL, new BigDecimal("110"),
			new BigDecimal("3"), 330L, 1L, 5_000L, NOW.minusMinutes(1), NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	private static Order order() {
		return Order.create(
			testUser(),
			account(),
			stockInstrument(),
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			"idem-key",
			"h".repeat(64),
			NOW);
	}

	private static com.finplay.api.market.domain.Instrument stockInstrument() {
		return com.finplay.api.market.domain.Instrument.create(
			com.finplay.api.market.domain.Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
	}

	private static Account account() {
		return Account.create(testUser(), Market.STOCK, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
