// JournalService.createBuyJournal·createSellJournal·updateSellJournal·updateBuyJournal의 검증 순서·저장 인자·예외 변환을 검증하는 단위 테스트다.
package com.finplay.api.journal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.journal.domain.BuyTradeJournal;
import com.finplay.api.journal.domain.SellTradeJournal;
import com.finplay.api.journal.dto.response.BuyJournalResponse;
import com.finplay.api.journal.dto.response.BuyJournalUpdateResponse;
import com.finplay.api.journal.dto.response.SellJournalResponse;
import com.finplay.api.journal.dto.response.SellJournalUpdateResponse;
import com.finplay.api.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.journal.repository.SellTradeJournalRepository;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class JournalServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long BUY_TRADE_ID = 5L;
	private static final Long SELL_TRADE_ID = 6L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-04T10:00:00Z");
	private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final TradeService tradeService = mock(TradeService.class);
	private final BuyTradeJournalRepository buyTradeJournalRepository = mock(BuyTradeJournalRepository.class);
	private final SellTradeJournalRepository sellTradeJournalRepository = mock(SellTradeJournalRepository.class);

	private final JournalService journalService = new JournalService(tradeService, buyTradeJournalRepository,
		sellTradeJournalRepository, FIXED_CLOCK);

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

	// 검증 순서 확인 — 타인 소유의 매수 체결은 getOwnedTrade 단계에서 403으로 끝나야 한다.
	// side 검사(400)까지 도달하면 이 테스트가 실패해 검증 순서 위반을 드러낸다.
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

	// 검증 순서 확인 — 타인 소유의 매수 체결은 getOwnedTrade 단계에서 403으로 끝나야 한다.
	// side 검사(400)나 회고 조회(404)까지 도달하면 이 테스트가 실패해 검증 순서 위반을 드러낸다.
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

	// 체결이 없는 404(getOwnedTrade 단계)와 회고가 없는 404(findBySellTradeId 단계)는
	// 같은 ErrorCode.NOT_FOUND를 쓰지만 트리거 지점이 다르다 — 바로 위 트레이드 미존재 테스트와 대비해서 본다.
	// 이 테스트는 본인 소유의 매도 체결까지는 통과했는데 회고가 없어서 실패하는 경로를 확인한다.
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
			tradeService, buyTradeJournalRepository, sellTradeJournalRepository, firstEditClock);
		JournalService secondEditService = new JournalService(
			tradeService, buyTradeJournalRepository, sellTradeJournalRepository, secondEditClock);

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

	// 검증 순서 확인 — 타인 소유의 매도 체결은 getOwnedTrade 단계에서 403으로 끝나야 한다.
	// side 검사(400)나 회고 조회(404)까지 도달하면 이 테스트가 실패해 검증 순서 위반을 드러낸다.
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

	// 체결이 없는 404(getOwnedTrade 단계)와 회고가 없는 404(findByBuyTradeId 단계)는
	// 같은 ErrorCode.NOT_FOUND를 쓰지만 트리거 지점이 다르다 — 바로 위 트레이드 미존재 테스트와 대비해서 본다.
	// 이 테스트는 본인 소유의 매수 체결까지는 통과했는데 회고가 없어서 실패하는 경로를 확인한다.
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
			tradeService, buyTradeJournalRepository, sellTradeJournalRepository, firstEditClock);
		JournalService secondEditService = new JournalService(
			tradeService, buyTradeJournalRepository, sellTradeJournalRepository, secondEditClock);

		BuyJournalUpdateResponse firstResponse = firstEditService.updateBuyJournal(USER_ID, BUY_TRADE_ID, "첫 수정 내용");
		BuyJournalUpdateResponse secondResponse = secondEditService.updateBuyJournal(USER_ID, BUY_TRADE_ID,
			"두 번째 수정 내용");

		assertThat(secondResponse.updatedAt()).isAfter(firstResponse.updatedAt());
		assertThat(secondResponse.content()).isEqualTo("두 번째 수정 내용");
		assertThat(journal.getContent()).isEqualTo("두 번째 수정 내용");
		assertThat(journal.getUpdatedAt()).isEqualTo(secondResponse.updatedAt());
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

	private static com.finplay.api.market.domain.StockReplaySession stockSession() {
		return com.finplay.api.market.domain.StockReplaySession.ready(
			NOW.toLocalDate(), NOW.toLocalDate(), NOW, NOW);
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
