package com.finplay.api.domain.journal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.journal.dto.response.BuyJournalResponse;
import com.finplay.api.domain.journal.dto.response.BuyJournalUpdateResponse;
import com.finplay.api.domain.journal.dto.response.JournalListItemResponse;
import com.finplay.api.domain.journal.dto.response.JournalListResponse;
import com.finplay.api.domain.journal.dto.response.SellJournalResponse;
import com.finplay.api.domain.journal.dto.response.SellJournalUpdateResponse;
import com.finplay.api.domain.journal.entity.BuyTradeJournal;
import com.finplay.api.domain.journal.entity.SellTradeJournal;
import com.finplay.api.domain.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.domain.journal.repository.SellTradeJournalRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class JournalServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long BUY_TRADE_ID = 5L;
	private static final Long SELL_TRADE_ID = 6L;
	private static final Long ACCOUNT_ID = 10L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-04T10:00:00Z");
	private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final TradeService tradeService = mock(TradeService.class);
	private final AccountService accountService = mock(AccountService.class);
	private final BuyTradeJournalRepository buyTradeJournalRepository = mock(BuyTradeJournalRepository.class);
	private final SellTradeJournalRepository sellTradeJournalRepository = mock(SellTradeJournalRepository.class);

	private final JournalService journalService = new JournalService(tradeService, accountService,
		buyTradeJournalRepository, sellTradeJournalRepository, FIXED_CLOCK);

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

	@Test
	void createSellJournalSavesJournalWithFixedClockAndReturnsAllFields() {
		Trade trade = sellTrade(SELL_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
		when(sellTradeJournalRepository.existsBySellTradeId(SELL_TRADE_ID)).thenReturn(false);
		when(sellTradeJournalRepository.saveAndFlush(any(SellTradeJournal.class))).thenAnswer(invocation -> {
			SellTradeJournal saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 200L);
			return saved;
		});

		SellJournalResponse response = journalService.createSellJournal(USER_ID, SELL_TRADE_ID, "익절 복기");

		ArgumentCaptor<SellTradeJournal> captor = ArgumentCaptor.forClass(SellTradeJournal.class);
		verify(sellTradeJournalRepository).saveAndFlush(captor.capture());
		SellTradeJournal savedArg = captor.getValue();
		assertThat(savedArg.getSellTrade()).isSameAs(trade);
		assertThat(savedArg.getContent()).isEqualTo("익절 복기");
		assertThat(savedArg.getCreatedAt()).isEqualTo(NOW);

		assertThat(response.journalId()).isEqualTo(200L);
		assertThat(response.sellTradeId()).isEqualTo(SELL_TRADE_ID);
		assertThat(response.content()).isEqualTo("익절 복기");
		assertThat(response.createdAt()).isEqualTo(NOW);
	}

	@Test
	void createSellJournalPropagatesNotFoundWhenTradeDoesNotExist() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> journalService.createSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verify(sellTradeJournalRepository, never()).existsBySellTradeId(any());
		verify(sellTradeJournalRepository, never()).saveAndFlush(any());
	}

	@Test
	void createSellJournalPropagatesForbiddenWhenTradeOwnedByAnotherUser() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> journalService.createSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));

		verify(sellTradeJournalRepository, never()).existsBySellTradeId(any());
		verify(sellTradeJournalRepository, never()).saveAndFlush(any());
	}

	@Test
	void createSellJournalThrowsValidationErrorWhenTradeIsNotSellSide() {
		Trade buyTrade = buyTrade(SELL_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(buyTrade);

		assertThatThrownBy(() -> journalService.createSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(sellTradeJournalRepository, never()).existsBySellTradeId(any());
		verify(sellTradeJournalRepository, never()).saveAndFlush(any());
	}

	@Test
	void createSellJournalThrowsDuplicateResourceWhenJournalAlreadyExists() {
		Trade trade = sellTrade(SELL_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
		when(sellTradeJournalRepository.existsBySellTradeId(SELL_TRADE_ID)).thenReturn(true);

		assertThatThrownBy(() -> journalService.createSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.DUPLICATE_RESOURCE));

		verify(sellTradeJournalRepository, never()).saveAndFlush(any());
	}

	@Test
	void createSellJournalReturnsForbiddenNotValidationErrorForOtherUsersBuyTrade() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> journalService.createSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN)
				.isNotEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void createSellJournalConvertsDataIntegrityViolationToDuplicateResource() {
		Trade trade = sellTrade(SELL_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
		when(sellTradeJournalRepository.existsBySellTradeId(SELL_TRADE_ID)).thenReturn(false);
		when(sellTradeJournalRepository.saveAndFlush(any(SellTradeJournal.class)))
			.thenThrow(new DataIntegrityViolationException("duplicate key"));

		assertThatThrownBy(() -> journalService.createSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
	}

	@Test
	void updateSellJournalCallsUpdateContentWithNewContentAndFixedClockAndReturnsAllFields() {
		Trade trade = sellTrade(SELL_TRADE_ID);
		LocalDateTime originalCreatedAt = NOW.minusDays(3);
		SellTradeJournal realJournal = SellTradeJournal.of(trade, "원래 내용", originalCreatedAt);
		ReflectionTestUtils.setField(realJournal, "id", 300L);
		SellTradeJournal journal = spy(realJournal);
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
		when(sellTradeJournalRepository.findBySellTradeId(SELL_TRADE_ID)).thenReturn(Optional.of(journal));

		SellJournalUpdateResponse response = journalService.updateSellJournal(USER_ID, SELL_TRADE_ID, "수정된 내용");

		verify(journal).updateContent("수정된 내용", NOW);

		assertThat(response.journalId()).isEqualTo(300L);
		assertThat(response.sellTradeId()).isEqualTo(SELL_TRADE_ID);
		assertThat(response.content()).isEqualTo("수정된 내용");
		assertThat(response.createdAt()).isEqualTo(originalCreatedAt);
		assertThat(response.updatedAt()).isEqualTo(NOW);
	}

	@Test
	void updateSellJournalPropagatesNotFoundWhenTradeDoesNotExist() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> journalService.updateSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verify(sellTradeJournalRepository, never()).findBySellTradeId(any());
	}

	@Test
	void updateSellJournalPropagatesForbiddenWhenTradeOwnedByAnotherUser() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> journalService.updateSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));

		verify(sellTradeJournalRepository, never()).findBySellTradeId(any());
	}

	@Test
	void updateSellJournalReturnsForbiddenNotValidationErrorForOtherUsersBuyTrade() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> journalService.updateSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN)
				.isNotEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(sellTradeJournalRepository, never()).findBySellTradeId(any());
	}

	@Test
	void updateSellJournalThrowsValidationErrorWhenTradeIsNotSellSide() {
		Trade buyTrade = buyTrade(SELL_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(buyTrade);

		assertThatThrownBy(() -> journalService.updateSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(sellTradeJournalRepository, never()).findBySellTradeId(any());
	}

	@Test
	void updateSellJournalThrowsNotFoundWhenJournalDoesNotExistEvenThoughTradeIsOwnedSellTrade() {
		Trade trade = sellTrade(SELL_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
		when(sellTradeJournalRepository.findBySellTradeId(SELL_TRADE_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> journalService.updateSellJournal(USER_ID, SELL_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verify(tradeService).getOwnedTrade(USER_ID, SELL_TRADE_ID);
		verify(sellTradeJournalRepository).findBySellTradeId(SELL_TRADE_ID);
	}

	@Test
	void updateSellJournalSecondEditOverwritesFirstAndAdvancesUpdatedAt() {
		Trade trade = sellTrade(SELL_TRADE_ID);
		SellTradeJournal journal = SellTradeJournal.of(trade, "첫 번째 내용", NOW.minusDays(1));
		ReflectionTestUtils.setField(journal, "id", 300L);
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
		when(sellTradeJournalRepository.findBySellTradeId(SELL_TRADE_ID)).thenReturn(Optional.of(journal));

		Clock firstEditClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		Clock secondEditClock = Clock.fixed(FIXED_INSTANT.plusSeconds(3600), ZoneOffset.UTC);
		JournalService firstEditService = new JournalService(
			tradeService, accountService, buyTradeJournalRepository, sellTradeJournalRepository, firstEditClock);
		JournalService secondEditService = new JournalService(
			tradeService, accountService, buyTradeJournalRepository, sellTradeJournalRepository, secondEditClock);

		SellJournalUpdateResponse firstResponse = firstEditService.updateSellJournal(USER_ID, SELL_TRADE_ID, "첫 수정 내용");
		SellJournalUpdateResponse secondResponse = secondEditService.updateSellJournal(USER_ID, SELL_TRADE_ID,
			"두 번째 수정 내용");

		assertThat(secondResponse.updatedAt()).isAfter(firstResponse.updatedAt());
		assertThat(secondResponse.content()).isEqualTo("두 번째 수정 내용");
		assertThat(journal.getContent()).isEqualTo("두 번째 수정 내용");
		assertThat(journal.getUpdatedAt()).isEqualTo(secondResponse.updatedAt());
	}

	@Test
	void updateBuyJournalCallsUpdateContentWithNewContentAndFixedClockAndReturnsAllFields() {
		Trade trade = buyTrade(BUY_TRADE_ID);
		LocalDateTime originalCreatedAt = NOW.minusDays(3);
		BuyTradeJournal realJournal = BuyTradeJournal.of(trade, "원래 내용", originalCreatedAt);
		ReflectionTestUtils.setField(realJournal, "id", 400L);
		BuyTradeJournal journal = spy(realJournal);
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenReturn(trade);
		when(buyTradeJournalRepository.findByBuyTradeId(BUY_TRADE_ID)).thenReturn(Optional.of(journal));

		BuyJournalUpdateResponse response = journalService.updateBuyJournal(USER_ID, BUY_TRADE_ID, "수정된 내용");

		verify(journal).updateContent("수정된 내용", NOW);

		assertThat(response.journalId()).isEqualTo(400L);
		assertThat(response.buyTradeId()).isEqualTo(BUY_TRADE_ID);
		assertThat(response.content()).isEqualTo("수정된 내용");
		assertThat(response.createdAt()).isEqualTo(originalCreatedAt);
		assertThat(response.updatedAt()).isEqualTo(NOW);
	}

	@Test
	void updateBuyJournalPropagatesNotFoundWhenTradeDoesNotExist() {
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> journalService.updateBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verify(buyTradeJournalRepository, never()).findByBuyTradeId(any());
	}

	@Test
	void updateBuyJournalPropagatesForbiddenWhenTradeOwnedByAnotherUser() {
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> journalService.updateBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));

		verify(buyTradeJournalRepository, never()).findByBuyTradeId(any());
	}

	@Test
	void updateBuyJournalReturnsForbiddenNotValidationErrorForOtherUsersSellTrade() {
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> journalService.updateBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN)
				.isNotEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(buyTradeJournalRepository, never()).findByBuyTradeId(any());
	}

	@Test
	void updateBuyJournalThrowsValidationErrorWhenTradeIsNotBuySide() {
		Trade sellTrade = sellTrade(BUY_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenReturn(sellTrade);

		assertThatThrownBy(() -> journalService.updateBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(buyTradeJournalRepository, never()).findByBuyTradeId(any());
	}

	@Test
	void updateBuyJournalThrowsNotFoundWhenJournalDoesNotExistEvenThoughTradeIsOwnedBuyTrade() {
		Trade trade = buyTrade(BUY_TRADE_ID);
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenReturn(trade);
		when(buyTradeJournalRepository.findByBuyTradeId(BUY_TRADE_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> journalService.updateBuyJournal(USER_ID, BUY_TRADE_ID, "내용"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verify(tradeService).getOwnedTrade(USER_ID, BUY_TRADE_ID);
		verify(buyTradeJournalRepository).findByBuyTradeId(BUY_TRADE_ID);
	}

	@Test
	void updateBuyJournalSecondEditOverwritesFirstAndAdvancesUpdatedAt() {
		Trade trade = buyTrade(BUY_TRADE_ID);
		BuyTradeJournal journal = BuyTradeJournal.of(trade, "첫 번째 내용", NOW.minusDays(1));
		ReflectionTestUtils.setField(journal, "id", 400L);
		when(tradeService.getOwnedTrade(USER_ID, BUY_TRADE_ID)).thenReturn(trade);
		when(buyTradeJournalRepository.findByBuyTradeId(BUY_TRADE_ID)).thenReturn(Optional.of(journal));

		Clock firstEditClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		Clock secondEditClock = Clock.fixed(FIXED_INSTANT.plusSeconds(3600), ZoneOffset.UTC);
		JournalService firstEditService = new JournalService(
			tradeService, accountService, buyTradeJournalRepository, sellTradeJournalRepository, firstEditClock);
		JournalService secondEditService = new JournalService(
			tradeService, accountService, buyTradeJournalRepository, sellTradeJournalRepository, secondEditClock);

		BuyJournalUpdateResponse firstResponse = firstEditService.updateBuyJournal(USER_ID, BUY_TRADE_ID, "첫 수정 내용");
		BuyJournalUpdateResponse secondResponse = secondEditService.updateBuyJournal(USER_ID, BUY_TRADE_ID,
			"두 번째 수정 내용");

		assertThat(secondResponse.updatedAt()).isAfter(firstResponse.updatedAt());
		assertThat(secondResponse.content()).isEqualTo("두 번째 수정 내용");
		assertThat(journal.getContent()).isEqualTo("두 번째 수정 내용");
		assertThat(journal.getUpdatedAt()).isEqualTo(secondResponse.updatedAt());
	}

	@Test
	void getMyJournalEntriesPropagatesNotFoundWhenAccountDoesNotExist() {
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> journalService.getMyJournalEntries(USER_ID, Market.STOCK, null, 20))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verify(buyTradeJournalRepository, never()).findByAccountIdWithCursor(any(), any(), any(), anyInt());
		verify(sellTradeJournalRepository, never()).findByAccountIdWithCursor(any(), any(), any(), anyInt());
	}

	@Test
	void getMyJournalEntriesReturnsMixedListSortedByCreatedAtDescending() {
		Account account = accountWithId(ACCOUNT_ID);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);

		BuyTradeJournal oldBuy = BuyTradeJournal.of(buyTrade(1L), "오래된 매수 회고", NOW.minusDays(2));
		SellTradeJournal midSell = SellTradeJournal.of(sellTrade(2L), "중간 매도 회고", NOW.minusDays(1));
		BuyTradeJournal newBuy = BuyTradeJournal.of(buyTrade(3L), "최신 매수 회고", NOW);

		when(buyTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(21)))
			.thenReturn(List.of(oldBuy, newBuy));
		when(sellTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(21)))
			.thenReturn(List.of(midSell));

		JournalListResponse response = journalService.getMyJournalEntries(USER_ID, Market.STOCK, null, 20);

		assertThat(response.content()).extracting(JournalListItemResponse::content)
			.containsExactly("최신 매수 회고", "중간 매도 회고", "오래된 매수 회고");
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyJournalEntriesTieBreaksBySameCreatedAtByLargerTradeIdFirst() {
		Account account = accountWithId(ACCOUNT_ID);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);

		BuyTradeJournal buyAtSameInstant = BuyTradeJournal.of(buyTrade(5L), "매수 회고", NOW);
		SellTradeJournal sellAtSameInstant = SellTradeJournal.of(sellTrade(7L), "매도 회고", NOW);

		when(buyTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(21)))
			.thenReturn(List.of(buyAtSameInstant));
		when(sellTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(21)))
			.thenReturn(List.of(sellAtSameInstant));

		JournalListResponse response = journalService.getMyJournalEntries(USER_ID, Market.STOCK, null, 20);

		assertThat(response.content()).extracting(JournalListItemResponse::content)
			.containsExactly("매도 회고", "매수 회고");
	}

	@Test
	void getMyJournalEntriesReturnsNoNextPageWhenMergedResultExactlyMatchesLimit() {
		Account account = accountWithId(ACCOUNT_ID);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);

		BuyTradeJournal first = BuyTradeJournal.of(buyTrade(1L), "회고1", NOW.minusMinutes(3));
		BuyTradeJournal second = BuyTradeJournal.of(buyTrade(2L), "회고2", NOW.minusMinutes(2));
		BuyTradeJournal third = BuyTradeJournal.of(buyTrade(3L), "회고3", NOW.minusMinutes(1));

		when(buyTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(4)))
			.thenReturn(List.of(first, second, third));
		when(sellTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(4)))
			.thenReturn(List.of());

		JournalListResponse response = journalService.getMyJournalEntries(USER_ID, Market.STOCK, null, 3);

		assertThat(response.content()).hasSize(3);
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyJournalEntriesReturnsNextPageWhenMergedResultExceedsLimit() {
		Account account = accountWithId(ACCOUNT_ID);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);

		BuyTradeJournal first = BuyTradeJournal.of(buyTrade(1L), "회고1", NOW.minusMinutes(3));
		BuyTradeJournal second = BuyTradeJournal.of(buyTrade(2L), "회고2", NOW.minusMinutes(2));
		BuyTradeJournal third = BuyTradeJournal.of(buyTrade(3L), "회고3", NOW.minusMinutes(1));

		when(buyTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(3)))
			.thenReturn(List.of(first, second, third));
		when(sellTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(3)))
			.thenReturn(List.of());

		JournalListResponse response = journalService.getMyJournalEntries(USER_ID, Market.STOCK, null, 2);

		assertThat(response.content()).hasSize(2);
		assertThat(response.content()).extracting(JournalListItemResponse::content)
			.containsExactly("회고3", "회고2");
		assertThat(response.hasNext()).isTrue();
		assertThat(response.nextCursor()).isEqualTo(JournalCursor.encode(NOW.minusMinutes(2), 2L));
	}

	@Test
	void getMyJournalEntriesReturnsEmptyContentWhenBothRepositoriesAreEmpty() {
		Account account = accountWithId(ACCOUNT_ID);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);
		when(buyTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());
		when(sellTradeJournalRepository.findByAccountIdWithCursor(eq(ACCOUNT_ID), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());

		JournalListResponse response = journalService.getMyJournalEntries(USER_ID, Market.STOCK, null, 20);

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyJournalEntriesPassesSameCursorArgumentsToBothRepositories() {
		Account account = accountWithId(ACCOUNT_ID);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);
		String cursor = JournalCursor.encode(NOW.minusDays(1), 42L);
		when(buyTradeJournalRepository.findByAccountIdWithCursor(any(), any(), any(), anyInt())).thenReturn(List.of());
		when(sellTradeJournalRepository.findByAccountIdWithCursor(any(), any(), any(), anyInt())).thenReturn(List.of());

		journalService.getMyJournalEntries(USER_ID, Market.STOCK, cursor, 15);

		ArgumentCaptor<LocalDateTime> buyCreatedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
		ArgumentCaptor<Long> buyTradeIdCaptor = ArgumentCaptor.forClass(Long.class);
		verify(buyTradeJournalRepository)
			.findByAccountIdWithCursor(eq(ACCOUNT_ID), buyCreatedAtCaptor.capture(), buyTradeIdCaptor.capture(),
				eq(16));

		ArgumentCaptor<LocalDateTime> sellCreatedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
		ArgumentCaptor<Long> sellTradeIdCaptor = ArgumentCaptor.forClass(Long.class);
		verify(sellTradeJournalRepository)
			.findByAccountIdWithCursor(eq(ACCOUNT_ID), sellCreatedAtCaptor.capture(), sellTradeIdCaptor.capture(),
				eq(16));

		assertThat(buyCreatedAtCaptor.getValue()).isEqualTo(NOW.minusDays(1));
		assertThat(buyTradeIdCaptor.getValue()).isEqualTo(42L);
		assertThat(buyCreatedAtCaptor.getValue()).isEqualTo(sellCreatedAtCaptor.getValue());
		assertThat(buyTradeIdCaptor.getValue()).isEqualTo(sellTradeIdCaptor.getValue());
	}

	@Test
	void findSellJournalContentReturnsEmptyWhenSellJournalDoesNotExist() {
		when(sellTradeJournalRepository.findBySellTradeId(SELL_TRADE_ID)).thenReturn(Optional.empty());

		assertThat(journalService.findSellJournalContent(SELL_TRADE_ID)).isEmpty();
		verify(tradeService, never()).getOwnedTrade(any(), any());
	}

	@Test
	void findSellJournalContentReturnsTradeIdContentAndUpdatedAt() {
		SellTradeJournal journal = SellTradeJournal.of(sellTrade(SELL_TRADE_ID), "손절 기준을 못 지켰다.", NOW);
		journal.updateContent("손절 기준을 못 지켰다. 다음엔 지킨다.", NOW.plusDays(1));
		when(sellTradeJournalRepository.findBySellTradeId(SELL_TRADE_ID)).thenReturn(Optional.of(journal));

		assertThat(journalService.findSellJournalContent(SELL_TRADE_ID))
			.contains(new JournalContentDto(SELL_TRADE_ID, "손절 기준을 못 지켰다. 다음엔 지킨다.", NOW.plusDays(1)));
	}

	@Test
	void findBuyJournalContentsReturnsEmptyWithoutQueryingWhenBuyTradeIdsAreEmpty() {
		assertThat(journalService.findBuyJournalContents(List.of())).isEmpty();

		verify(buyTradeJournalRepository, never()).findAllByBuyTradeIdIn(any());
	}

	@Test
	void findBuyJournalContentsReturnsOnlyTheTradesThatHaveAJournal() {
		BuyTradeJournal journal = BuyTradeJournal.of(buyTrade(BUY_TRADE_ID), "실적 발표 전 분할 매수.", NOW);
		when(buyTradeJournalRepository.findAllByBuyTradeIdIn(List.of(BUY_TRADE_ID, 99L)))
			.thenReturn(List.of(journal));

		assertThat(journalService.findBuyJournalContents(List.of(BUY_TRADE_ID, 99L)))
			.containsExactly(new JournalContentDto(BUY_TRADE_ID, "실적 발표 전 분할 매수.", NOW));
		verify(tradeService, never()).getOwnedTrade(any(), any());
	}

	private static Account accountWithId(Long id) {
		Account account = account();
		ReflectionTestUtils.setField(account, "id", id);
		return account;
	}

	private static Trade buyTrade(Long id) {
		Order order = order();
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, NOW.minusMinutes(1), NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	private static Trade sellTrade(Long id) {
		Order order = order();
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			OrderSide.SELL, new BigDecimal("110"),
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

	private static com.finplay.api.domain.market.entity.StockReplaySession stockSession() {
		return com.finplay.api.domain.market.entity.StockReplaySession.ready(
			NOW.toLocalDate(), NOW.toLocalDate(), NOW, NOW);
	}

	private static com.finplay.api.domain.market.entity.Instrument stockInstrument() {
		return com.finplay.api.domain.market.entity.Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
	}

	private static Account account() {
		return Account.create(testUser(), Market.STOCK, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
