// 매도 회고 서술 저장 컴포넌트의 소유권 재확인과 트랜잭션 경계를 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.TradeFeedback;
import com.finplay.api.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
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

// 이 클래스가 존재하는 이유가 트랜잭션 경계이므로(LLM 대기 중 커넥션 점유 방지) 그 구조를 단정으로 고정한다 —
// 경계가 무너지면 느려지기만 하고 동작으로는 드러나지 않는다.
class TradeFeedbackWriterTest {

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;
	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 5, 16, 0);

	// 기존 픽스처에는 투자일기가 없다 — 그때 프롬프트에 실린 일기가 없으므로 지문도 null이다(§FEED-013 결정 3).
	private static final String NO_JOURNAL = null;

	// 지문은 SHA-256 hex 64자다 — 값 자체는 대조에만 쓰이므로 길이만 컬럼 제약과 맞춘다.
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
		// 읽기 트랜잭션의 detached 엔티티가 아니라 이 트랜잭션에서 다시 읽은 관리 상태 엔티티를 건다.
		assertThat(saved.getTrade()).isSameAs(trade);
		assertThat(saved.getNarrative()).isEqualTo("템플릿 문장입니다.");
		assertThat(saved.getNarrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(saved.getGeneratedAt()).isEqualTo(GENERATED_AT);
		// 최초 생성이라 재생성 상태는 기본값이다 — 5번 항목이 이 값을 바꾼다.
		assertThat(saved.isNarrativeFinalized()).isFalse();
		assertThat(saved.getRegenerationAttempts()).isZero();
	}

	// 최초 저장에서 지문이 빠지면 저장된 값이 늘 null이라 그 뒤 모든 조회가 "지문 다름"으로 판정된다 —
	// 조회마다 LLM을 부르는데 응답은 정상 200이다(§FEED-013 결정 3).
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

	// --- 사유별 전이 (§FEED-013 결정 3) ---

	// 흐름·집단 사유만 성립했을 때의 기존 전이다 — 확정으로 닫고 그쪽 카운터만 올린다. 지문은 어느 사유든
	// 갱신한다: 그 프롬프트에도 현재 일기가 실렸다.
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

	// 여기서 확정 플래그를 켜면 흐름·집단 게이트가 열리기도 전에 닫혀 그 체결은 매도 후 흐름을 영원히
	// 반영하지 못한다 — 예외도 로그도 없다(불변식 1).
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

	// 이미 확정된 행에 일기 사유가 성립한 경우 — 확정은 그대로 두고 흐름·집단 카운터도 오르지 않아야 한다.
	// 확정 플래그를 다시 쓰는 구현은 이 케이스에서만 드러나지 않으므로 카운터로 함께 고정한다.
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

	// 한 번의 생성이 두 사유를 함께 반영했으므로 두 카운터가 모두 오르는 것이 맞다(불변식 2).
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

	// 템플릿 문장에는 일기도 매도 후 흐름도 없다 — 그런데 지문만 맞춰 두면 다음 조회가 "이미 반영됐다"고
	// 판정해 그 일기가 영원히 반영되지 않는다.
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

	// 재생성 경로도 create와 같은 경계여야 한다 — 애노테이션이 빠지면 전이가 더티 체킹 대상이 아니게 되고
	// 예외도 로그도 없이 재생성이 매 조회마다 반복된다.
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

	// 회원별 쓰기가 처음 생기는 자리다 — 남의 체결에 행이 붙으면 원장은 그대로여도 회고가 새어 나간다.
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

	// 저장만 트랜잭션으로 감싼다 — 애노테이션이 사라지면 UNIQUE 충돌·롤백 경계가 조용히 바뀐다.
	@Test
	@DisplayName("create에 @Transactional이 붙어 있고 readOnly가 아니다")
	void wrapsOnlyTheSaveInAWriteTransaction() throws Exception {
		Method create = TradeFeedbackWriter.class.getDeclaredMethod(
			"create", Long.class, Long.class, NarrativeResultDto.class, String.class, LocalDateTime.class);

		Transactional annotation = create.getAnnotation(Transactional.class);
		assertThat(annotation).isNotNull();
		assertThat(annotation.readOnly()).isFalse();
	}

	// 자기호출은 프록시를 타지 않으므로 경계를 옮기려면 별도 빈이어야 한다 — 이 클래스가 PostSellFeedbackService의
	// 내부 메서드가 아니라 독립 컴포넌트인 이유다.
	@Test
	@DisplayName("서술 저장 경로가 이 컴포넌트뿐이라 리포지터리 의존이 trade_feedbacks 하나다")
	void dependsOnlyOnTheTradeFeedbackRepository() {
		// @Slf4j가 만드는 Logger 같은 부수 필드는 제외하고 리포지터리 의존만 본다 — 규칙은 "쓰기 경로가
		// trade_feedbacks 하나"이지 필드 수가 아니다.
		assertThat(TradeFeedbackWriter.class.getDeclaredFields())
			.extracting(field -> field.getType().getSimpleName())
			.filteredOn(type -> type.endsWith("Repository"))
			.containsExactly("TradeFeedbackRepository");
	}

	// 전이는 엔티티가 소유하므로 mock이 아니라 실제 팩토리로 만든 행을 쓴다(docs/conventions/code.md). 이 경로는
	// 연관 체결을 읽지 않아 trade가 필요하지 않다.
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
		Account account = Account.create(user, com.finplay.api.account.domain.Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), "idem-key",
			"h".repeat(64), executedAt);
		return Trade.of(
			order, account, instrument, session, OrderSide.SELL, new BigDecimal("68500"), new BigDecimal("10"),
			685_000L, 102L, -15_207L, executedAt, executedAt);
	}
}
