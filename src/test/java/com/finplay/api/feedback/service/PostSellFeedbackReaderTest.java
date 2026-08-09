// 매도 직후 피드백 오케스트레이터의 컨텍스트 로드 → 시장 분기 → 조립 위임과 예외 전파를 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

// tasks-282.md 1번 항목이 이 파일에 남긴 책임은 **오케스트레이션뿐**이다 — 검증 순서·배분 조회는
// PostSellFeedbackContextReaderTest가, 주식 조립은 StockPostSellFeedbackReaderTest가, 코인 조립은
// CryptoPostSellFeedbackReaderTest가 본다. 401·404·403·400의 HTTP 매핑은 PostSellFeedbackControllerTest가,
// 서술 생성·재사용은 PostSellFeedbackServiceTest가, 종단은 PostSellFeedbackIntegrationTest가 맡는다.
//
// **이 클래스에 @Transactional이 없어야 한다는 결정(spec §FEED-012 결정 5)은 여기서 검증할 수 없다** —
// 애노테이션 유무는 프록시가 붙는 실행 시점의 성질이라 mock 위에서는 드러나지 않는다. 트랜잭션 경계는
// tasks-282.md 3번 항목의 통합 테스트가 실제 DB로 고정한다.
class PostSellFeedbackReaderTest {

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SELL_SERVICE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);

	private final PostSellFeedbackContextReader postSellFeedbackContextReader = mock(
		PostSellFeedbackContextReader.class);

	private final StockPostSellFeedbackReader stockPostSellFeedbackReader = mock(StockPostSellFeedbackReader.class);

	private final CryptoPostSellFeedbackReader cryptoPostSellFeedbackReader = mock(CryptoPostSellFeedbackReader.class);

	private final PostSellFeedbackReader postSellFeedbackReader = new PostSellFeedbackReader(
		postSellFeedbackContextReader, stockPostSellFeedbackReader, cryptoPostSellFeedbackReader);

	// --- 시장 분기 ---

	@Test
	@DisplayName("주식 매도 체결은 주식 조립에 위임하고 그 응답을 그대로 돌려준다")
	void delegatesStockSellTradeToTheStockReader() {
		Trade trade = stockSellTrade();
		SellAllocationSummaryDto allocation = allocation();
		givenContext(trade, allocation);
		PostSellFeedbackResponse assembled = assembledResponse();
		when(stockPostSellFeedbackReader.read(trade, allocation)).thenReturn(assembled);

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		// 컨텍스트가 읽어 온 그 인스턴스를 그대로 넘긴다 — 조립이 다른 체결을 보면 응답 전체가 남의 값이 된다.
		verify(stockPostSellFeedbackReader).read(trade, allocation);
		assertThat(response).isSameAs(assembled);
		// 코인 경로로 새지 않는다.
		verifyNoInteractions(cryptoPostSellFeedbackReader);
	}

	// 이슈 #275가 이 자리를 뒤집었다 — 코인 매도 체결은 더 이상 400이 아니라 200이고, 조립은 코인 전담
	// 컴포넌트가 맡는다(§FEED-012).
	@Test
	@DisplayName("코인 매도 체결은 400이 아니라 코인 조립에 위임하고 그 응답을 그대로 돌려준다")
	void delegatesCryptoSellTradeToTheCryptoReaderInsteadOfRejectingIt() {
		Trade cryptoTrade = cryptoSellTrade();
		SellAllocationSummaryDto allocation = allocation();
		givenContext(cryptoTrade, allocation);
		PostSellFeedbackResponse assembled = assembledResponse();
		when(cryptoPostSellFeedbackReader.read(cryptoTrade, allocation)).thenReturn(assembled);

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		verify(cryptoPostSellFeedbackReader).read(cryptoTrade, allocation);
		assertThat(response).isSameAs(assembled);
		// 주식 조립 경로로 새지 않는다 — 코인에는 재생세션이 없어 그쪽으로 가면 그 자리에서 터진다.
		verifyNoInteractions(stockPostSellFeedbackReader);
	}

	// --- 위임 순서 ---

	@Test
	@DisplayName("컨텍스트를 그 회원·체결 id로 먼저 읽고 그 뒤에 조립에 넘긴다")
	void loadsTheContextBeforeAssembling() {
		Trade trade = stockSellTrade();
		SellAllocationSummaryDto allocation = allocation();
		givenContext(trade, allocation);

		postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		InOrder inOrder = inOrder(postSellFeedbackContextReader, stockPostSellFeedbackReader);
		inOrder.verify(postSellFeedbackContextReader).loadContext(USER_ID, SELL_TRADE_ID);
		inOrder.verify(stockPostSellFeedbackReader).read(trade, allocation);
	}

	// --- 예외 전파 ---

	// 검증은 전부 컨텍스트 로드가 마쳤다(404 → 403 → 400). 오케스트레이터가 할 일은 그것을 삼키지 않고
	// 그대로 흘리면서 **조립을 시작하지 않는 것**이다 — 조립을 먼저 부르면 남의 체결로도 캔들·LLM이 한 번 돈다.
	@ParameterizedTest
	@EnumSource(value = ErrorCode.class, names = {"NOT_FOUND", "FORBIDDEN", "VALIDATION_ERROR"})
	@DisplayName("컨텍스트 로드가 던진 404·403·400을 그대로 전파하고 조립을 시작하지 않는다")
	void propagatesContextLoadFailuresWithoutAssembling(ErrorCode errorCode) {
		when(postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(errorCode));

		assertThatThrownBy(() -> postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode()).isEqualTo(errorCode));

		verifyNoInteractions(stockPostSellFeedbackReader, cryptoPostSellFeedbackReader);
	}

	// --- 픽스처 ---

	private void givenContext(Trade trade, SellAllocationSummaryDto allocation) {
		when(postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID))
			.thenReturn(new PostSellFeedbackContext(trade, allocation));
	}

	/**
	 * 조립 리더가 돌려준 응답을 오케스트레이터가 <b>그대로</b> 흘리는지만 보는 표식이다 — 값의 내용은 각
	 * 조립 리더의 전담 테스트가 본다. 필드를 채우지 않는 이유가 그것이다.
	 */
	private static PostSellFeedbackResponse assembledResponse() {
		return new PostSellFeedbackResponse(
			SELL_TRADE_ID, INSTRUMENT_ID, "005930", "삼성전자", null, null, null, null, null, 0L, null, null, null,
			true, null, null, null, null, null, null, null, null, List.of(), null, null, null, null, null, null);
	}

	private static SellAllocationSummaryDto allocation() {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(SELL_SERVICE_DATE, BUY_TIME),
			ORIGIN_TRADE_DATE,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(ORIGIN_TRADE_DATE));
	}

	private static Trade stockSellTrade() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		LocalDateTime resolvedAt = LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(8, 40));
		return trade(
			instrument,
			StockReplaySession.ready(SELL_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt),
			new BigDecimal("68500"), 685_000L, 102L, -15_207L);
	}

	// 코인 체결에는 재생세션이 없다 (Trade가 그것을 강제한다).
	private static Trade cryptoSellTrade() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1"), 5_000L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return trade(instrument, null, new BigDecimal("100000000"), 100_000_000L, 50_000L, 1_000L);
	}

	private static Trade trade(
		Instrument instrument,
		StockReplaySession session,
		BigDecimal price,
		long amount,
		long fee,
		Long realizedPnl) {
		LocalDateTime executedAt = LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME);
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, com.finplay.api.account.domain.Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), "idem-key",
			"h".repeat(64), executedAt);
		Trade trade = Trade.of(
			order, account, instrument, session, OrderSide.SELL, price, new BigDecimal("10"), amount, fee,
			realizedPnl, executedAt, executedAt);
		ReflectionTestUtils.setField(trade, "id", SELL_TRADE_ID);
		return trade;
	}
}
