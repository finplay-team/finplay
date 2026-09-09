package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.TradeFeedback;
import com.finplay.api.domain.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class TradeFeedbackWriterTest {

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;
	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 5, 16, 0);

	private static final String NO_JOURNAL = null;

	private static final String OLD_FINGERPRINT = "a".repeat(64);

	private static final String FINGERPRINT = "b".repeat(64);

	private final TradeService tradeService = mock(TradeService.class);

	private final TradeFeedbackRepository tradeFeedbackRepository = mock(TradeFeedbackRepository.class);

	private final TradeFeedbackWriter tradeFeedbackWriter = new TradeFeedbackWriter(
		tradeService, tradeFeedbackRepository);

	@Test
	@DisplayName("체결을 트랜잭션 안에서 다시 읽어 그 체결에 서술 행을 붙인다")
	void savesTheNarrativeAgainstTheTradeItReadsInsideItsOwnTransaction() {
		Trade trade = sellTrade();
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
		when(tradeFeedbackRepository.save(any(TradeFeedback.class))).thenAnswer(call -> call.getArgument(0));

		tradeFeedbackWriter.create(
			USER_ID, SELL_TRADE_ID, NarrativeResultDto.template("템플릿 문장입니다."), NO_JOURNAL, GENERATED_AT);

		ArgumentCaptor<TradeFeedback> captor = ArgumentCaptor.forClass(TradeFeedback.class);
		verify(tradeFeedbackRepository).save(captor.capture());
		TradeFeedback saved = captor.getValue();
		assertThat(saved.getTrade()).isSameAs(trade);
		assertThat(saved.getNarrative()).isEqualTo("템플릿 문장입니다.");
		assertThat(saved.getNarrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(saved.getGeneratedAt()).isEqualTo(GENERATED_AT);
		assertThat(saved.isNarrativeFinalized()).isFalse();
		assertThat(saved.getRegenerationAttempts()).isZero();
	}

	@Test
	@DisplayName("최초 저장에 이번 프롬프트에 실린 일기의 지문이 담기고 일기 카운터는 0이다")
	void storesTheJournalFingerprintOfThePromptItWasBuiltFrom() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(sellTrade());
		when(tradeFeedbackRepository.save(any(TradeFeedback.class))).thenAnswer(call -> call.getArgument(0));

		tradeFeedbackWriter.create(
			USER_ID, SELL_TRADE_ID, NarrativeResultDto.llm("LLM 문장입니다."), FINGERPRINT, GENERATED_AT);

		ArgumentCaptor<TradeFeedback> captor = ArgumentCaptor.forClass(TradeFeedback.class);
		verify(tradeFeedbackRepository).save(captor.capture());
		assertThat(captor.getValue().getJournalFingerprint()).isEqualTo(FINGERPRINT);
		assertThat(captor.getValue().getJournalRegenerations()).isZero();
	}

	@Test
	@DisplayName("흐름·집단 사유만 성립하면 확정하고 regeneration_attempts만 올린다")
	void finalizesAndCountsOnlyTheGateCounterWhenOnlyTheGateReasonHolds() {
		TradeFeedback stored = storedFeedback(OLD_FINGERPRINT);
		givenStored(stored);

		tradeFeedbackWriter.applyRegenerated(
			SELL_TRADE_ID, NarrativeResultDto.llm("재생성된 문장입니다."), FINGERPRINT,
			new RegenerationReasons(false, true), GENERATED_AT);

		assertThat(stored.getNarrative()).isEqualTo("재생성된 문장입니다.");
		assertThat(stored.getJournalFingerprint()).isEqualTo(FINGERPRINT);
		assertThat(stored.isNarrativeFinalized()).isTrue();
		assertThat(stored.getRegenerationAttempts()).isEqualTo(1);
		assertThat(stored.getJournalRegenerations()).isZero();
		assertThat(stored.getGeneratedAt()).isEqualTo(GENERATED_AT);
	}

	@Test
	@DisplayName("일기 사유만 성립하면 narrative_finalized와 regeneration_attempts를 건드리지 않는다")
	void neverTouchesTheFinalizedFlagWhenOnlyTheJournalReasonHolds() {
		TradeFeedback stored = storedFeedback(OLD_FINGERPRINT);
		givenStored(stored);

		tradeFeedbackWriter.applyRegenerated(
			SELL_TRADE_ID, NarrativeResultDto.llm("일기를 반영한 문장입니다."), FINGERPRINT,
			new RegenerationReasons(true, false), GENERATED_AT);

		assertThat(stored.getNarrative()).isEqualTo("일기를 반영한 문장입니다.");
		assertThat(stored.getJournalFingerprint()).isEqualTo(FINGERPRINT);
		assertThat(stored.isNarrativeFinalized()).isFalse();
		assertThat(stored.getRegenerationAttempts()).isZero();
		assertThat(stored.getJournalRegenerations()).isEqualTo(1);
		assertThat(stored.getGeneratedAt()).isEqualTo(GENERATED_AT);
	}

	@Test
	@DisplayName("확정된 행에 일기 사유가 성립해도 확정 상태와 흐름·집단 카운터가 그대로다")
	void leavesTheSettledGateStateAloneWhenTheJournalReasonHoldsOnAFinalizedRow() {
		TradeFeedback stored = storedFeedback(OLD_FINGERPRINT);
		stored.applyRegeneratedNarrative(
			"확정된 문장입니다.", NarrativeSource.LLM, OLD_FINGERPRINT, GENERATED_AT.minusMinutes(10));
		givenStored(stored);

		tradeFeedbackWriter.applyRegenerated(
			SELL_TRADE_ID, NarrativeResultDto.llm("일기를 반영한 문장입니다."), FINGERPRINT,
			new RegenerationReasons(true, false), GENERATED_AT);

		assertThat(stored.isNarrativeFinalized()).isTrue();
		assertThat(stored.getRegenerationAttempts()).isEqualTo(1);
		assertThat(stored.getJournalRegenerations()).isEqualTo(1);
		assertThat(stored.getNarrative()).isEqualTo("일기를 반영한 문장입니다.");
	}

	@Test
	@DisplayName("두 사유가 함께 성립하면 확정하면서 두 카운터를 모두 올린다")
	void raisesBothCountersWhenBothReasonsHold() {
		TradeFeedback stored = storedFeedback(OLD_FINGERPRINT);
		givenStored(stored);

		tradeFeedbackWriter.applyRegenerated(
			SELL_TRADE_ID, NarrativeResultDto.llm("둘 다 반영한 문장입니다."), FINGERPRINT,
			new RegenerationReasons(true, true), GENERATED_AT);

		assertThat(stored.isNarrativeFinalized()).isTrue();
		assertThat(stored.getRegenerationAttempts()).isEqualTo(1);
		assertThat(stored.getJournalRegenerations()).isEqualTo(1);
		assertThat(stored.getJournalFingerprint()).isEqualTo(FINGERPRINT);
	}

	@Test
	@DisplayName("실패 누적은 서술·지문·확정 플래그를 그대로 두고 성립한 사유의 카운터만 올린다")
	void countsOnlyTheReasonThatHeldWhenTheRegenerationFailed() {
		TradeFeedback journalOnly = storedFeedback(OLD_FINGERPRINT);
		givenStored(journalOnly);
		tradeFeedbackWriter.recordFailedRegeneration(SELL_TRADE_ID, new RegenerationReasons(true, false));

		assertThat(journalOnly.getNarrative()).isEqualTo("기존 문장입니다.");
		assertThat(journalOnly.getJournalFingerprint()).isEqualTo(OLD_FINGERPRINT);
		assertThat(journalOnly.isNarrativeFinalized()).isFalse();
		assertThat(journalOnly.getGeneratedAt()).isEqualTo(GENERATED_AT.minusHours(1));
		assertThat(journalOnly.getJournalRegenerations()).isEqualTo(1);
		assertThat(journalOnly.getRegenerationAttempts()).isZero();

		TradeFeedback gateOnly = storedFeedback(OLD_FINGERPRINT);
		givenStored(gateOnly);
		tradeFeedbackWriter.recordFailedRegeneration(SELL_TRADE_ID, new RegenerationReasons(false, true));

		assertThat(gateOnly.getRegenerationAttempts()).isEqualTo(1);
		assertThat(gateOnly.getJournalRegenerations()).isZero();

		TradeFeedback both = storedFeedback(OLD_FINGERPRINT);
		givenStored(both);
		tradeFeedbackWriter.recordFailedRegeneration(SELL_TRADE_ID, new RegenerationReasons(true, true));

		assertThat(both.getRegenerationAttempts()).isEqualTo(1);
		assertThat(both.getJournalRegenerations()).isEqualTo(1);
	}

	@Test
	@DisplayName("재생성 저장 두 메서드에도 @Transactional이 붙어 있고 readOnly가 아니다")
	void wrapsBothRegenerationWritesInAWriteTransaction() throws Exception {
		Method applyRegenerated = TradeFeedbackWriter.class.getDeclaredMethod(
			"applyRegenerated", Long.class, NarrativeResultDto.class, String.class, RegenerationReasons.class,
			LocalDateTime.class);
		Method recordFailed = TradeFeedbackWriter.class.getDeclaredMethod(
			"recordFailedRegeneration", Long.class, RegenerationReasons.class);

		for (Method method : List.of(applyRegenerated, recordFailed)) {
			Transactional annotation = method.getAnnotation(Transactional.class);
			assertThat(annotation).as("%s에 @Transactional이 있어야 한다", method.getName()).isNotNull();
			assertThat(annotation.readOnly()).isFalse();
		}
	}

	@Test
	@DisplayName("타인 체결이면 쓰기 시점에 403으로 막히고 저장이 시도되지 않는다")
	void neverSavesWhenTheTradeBelongsToAnotherUser() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> tradeFeedbackWriter.create(
			USER_ID, SELL_TRADE_ID, NarrativeResultDto.llm("LLM 문장입니다."), NO_JOURNAL, GENERATED_AT))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));

		verify(tradeFeedbackRepository, never()).save(any());
	}

	@Test
	@DisplayName("create에 @Transactional이 붙어 있고 readOnly가 아니다")
	void wrapsOnlyTheSaveInAWriteTransaction() throws Exception {
		Method create = TradeFeedbackWriter.class.getDeclaredMethod(
			"create", Long.class, Long.class, NarrativeResultDto.class, String.class, LocalDateTime.class);

		Transactional annotation = create.getAnnotation(Transactional.class);
		assertThat(annotation).isNotNull();
		assertThat(annotation.readOnly()).isFalse();
	}

	@Test
	@DisplayName("서술 저장 경로가 이 컴포넌트뿐이라 리포지터리 의존이 trade_feedbacks 하나다")
	void dependsOnlyOnTheTradeFeedbackRepository() {
		assertThat(TradeFeedbackWriter.class.getDeclaredFields())
			.extracting(field -> field.getType().getSimpleName())
			.filteredOn(type -> type.endsWith("Repository"))
			.containsExactly("TradeFeedbackRepository");
	}

	private static TradeFeedback storedFeedback(String journalFingerprint) {
		return TradeFeedback.create(
			null, "기존 문장입니다.", NarrativeSource.LLM, journalFingerprint, GENERATED_AT.minusHours(1));
	}

	private void givenStored(TradeFeedback feedback) {
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID)).thenReturn(Optional.of(feedback));
	}

	private static Trade sellTrade() {
		LocalDate originTradeDate = LocalDate.of(2026, 7, 29);
		LocalDateTime executedAt = LocalDateTime.of(2026, 8, 4, 14, 40);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, executedAt);
		LocalDateTime resolvedAt = LocalDateTime.of(executedAt.toLocalDate(), LocalTime.of(8, 40));
		StockReplaySession session = StockReplaySession.ready(
			executedAt.toLocalDate(), originTradeDate, resolvedAt, executedAt);
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), "idem-key",
			"h".repeat(64), executedAt);
		return Trade.of(
			order, account, instrument, session, OrderSide.SELL, new BigDecimal("68500"), new BigDecimal("10"),
			685_000L, 102L, -15_207L, executedAt, executedAt);
	}
}
