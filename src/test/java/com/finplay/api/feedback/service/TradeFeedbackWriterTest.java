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
			USER_ID, SELL_TRADE_ID, NarrativeResultDto.template("템플릿 문장입니다."), GENERATED_AT);

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

	// 회원별 쓰기가 처음 생기는 자리다 — 남의 체결에 행이 붙으면 원장은 그대로여도 회고가 새어 나간다.
	@Test
	@DisplayName("타인 체결이면 쓰기 시점에 403으로 막히고 저장이 시도되지 않는다")
	void neverSavesWhenTheTradeBelongsToAnotherUser() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> tradeFeedbackWriter.create(
			USER_ID, SELL_TRADE_ID, NarrativeResultDto.llm("LLM 문장입니다."), GENERATED_AT))
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
			"create", Long.class, Long.class, NarrativeResultDto.class, LocalDateTime.class);

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
