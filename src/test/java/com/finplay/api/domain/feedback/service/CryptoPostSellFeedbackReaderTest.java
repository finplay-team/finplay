package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.CandleInterval;
import com.finplay.api.domain.market.service.CandleQueryService;
import com.finplay.api.domain.market.service.CryptoCandleDto;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class CryptoPostSellFeedbackReaderTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String SYMBOL = "BTC";
	private static final Long INSTRUMENT_ID = 7L;
	private static final Long SELL_TRADE_ID = 2L;

	private static final LocalDate SELL_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDateTime SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(2, 20, 41, 100_000_000));

	private static final LocalDateTime BUY_AT_199 = LocalDateTime.of(
		SELL_DATE.minusDays(1), LocalTime.of(23, 1, 17, 400_000_000));

	private static final LocalDateTime BUY_AT_200 = LocalDateTime.of(
		SELL_DATE.minusDays(1), LocalTime.of(23, 0, 17, 400_000_000));

	private static final BigDecimal QUANTITY = new BigDecimal("10");
	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	private static final long ALLOCATED_COST = 700_000L;
	private static final long ALLOCATED_BUY_FEE = 105L;

	private static final LocalDateTime GATE_OPENS_AT = SELL_DATE.plusDays(1).atStartOfDay();

	private static final PeerComparison PEER_COMPARISON = new PeerComparison(
		PostSellFeedbackStatus.NO_EVENT, null, null, null, null, null);

	private final CandleQueryService candleQueryService = mock(CandleQueryService.class);

	private final CryptoPostSellFeedbackDbReader cryptoPostSellFeedbackDbReader = mock(
		CryptoPostSellFeedbackDbReader.class);

	@BeforeEach
	void stubTheDbReaderWithItsEmptyHoldDefaults() {
		when(cryptoPostSellFeedbackDbReader.buildPeerComparison(any())).thenReturn(PEER_COMPARISON);
	}

	@Test
	@DisplayName("게이트 직전(매도일 23:59)이면 매도 후 흐름·반사실이 NOT_YET이고 가격 필드가 비어 있다")
	void keepsPostSellBlocksNotYetBeforeTheMidnightGate() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT.minusMinutes(1), BUY_AT_199);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().closeAt()).isNull();
		assertThat(response.postSellFlow().sellToCloseRate()).isNull();
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.postSellFlow().postSellHighAt()).isNull();

		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.counterfactuals().atClose()).isNull();
		assertThat(response.counterfactuals().atHoldHigh()).isNull();
		assertThat(response.counterfactuals().atFirstMoveAfterBuy()).isNull();

		assertThat(response.holdHighPrice()).isNotNull();
	}

	@Test
	@DisplayName("게이트가 열리는 첫 순간(다음 날 00:00)에 READY로 전이한다")
	void opensTheGateExactlyAtTheNextMidnight() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	@Test
	@DisplayName("이틀 뒤 조회에서도 READY가 유지된다 — 기준은 오늘이 아니라 그 체결의 날짜다")
	void keepsTheGateOpenWhenReadDaysLater() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT.plusDays(2).plusHours(9), BUY_AT_199);

		assertThat(response.postSellFlow().status())
			.as("오늘 자정을 기준으로 잡으면 여기서 NOT_YET으로 되돌아간다")
			.isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	@Test
	@DisplayName("보유 199분이면 holdHighBasis가 MINUTE이고 극값이 1분봉 close에서 나온다")
	void usesMinuteCandlesWhenTheHoldFitsIn199Minutes() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.holdingMinutes()).isEqualTo(199);
		assertThat(response.holdHighBasis()).isEqualTo(HoldHighBasis.MINUTE);
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30)));
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68000");
		assertThat(response.holdLowAt())
			.isEqualTo(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 1)));

		ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
		ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(candleQueryService)
			.getCryptoCandles(eq(SYMBOL), eq(CandleInterval.ONE_MINUTE), from.capture(), to.capture());
		assertThat(from.getValue()).isEqualTo(BUY_AT_199.withSecond(0).withNano(0));
		assertThat(to.getValue()).isEqualTo(SELL_AT.withSecond(0).withNano(0));
	}

	@Test
	@DisplayName("보유 200분이면 holdHighBasis가 DAILY이고 극값이 일봉 close에서 나온다")
	void fallsBackToDailyCandlesAt200Minutes() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_200);

		assertThat(response.holdingMinutes()).isEqualTo(200);
		assertThat(response.holdHighBasis()).isEqualTo(HoldHighBasis.DAILY);
		assertThat(response.holdHighPrice()).isEqualByComparingTo("71000");
		assertThat(response.holdHighAt())
			.isEqualTo(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 59)));
	}

	@Test
	@DisplayName("DAILY 표본은 매수일 ~ 매도 전날이고 매도일 일봉이 섞이지 않는다")
	void excludesTheSellDayCandleFromTheDailySample() {
		givenMinuteCandles(List.of());
		givenDailyCandles(multiDayCandles());
		LocalDateTime buyAt = LocalDateTime.of(SELL_DATE.minusDays(2), LocalTime.of(10, 0));
		LocalDateTime sellAt = LocalDateTime.of(SELL_DATE, LocalTime.of(12, 0));

		PostSellFeedbackResponse response = read(GATE_OPENS_AT.plusDays(1), buyAt, sellAt);

		assertThat(response.holdHighBasis()).isEqualTo(HoldHighBasis.DAILY);
		assertThat(response.holdHighPrice())
			.as("매도일 일봉이 표본에 들어오면 보유하지 않은 구간의 가격이 최고가가 된다")
			.isEqualByComparingTo("71000");
		assertThat(response.holdHighAt())
			.isEqualTo(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 59)));
		assertThat(response.holdLowPrice()).isEqualByComparingTo("70000");

		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("90000");
	}

	@Test
	@DisplayName("같은 날 안에서 199분 초과 보유면 극값·비율·atHoldHigh가 전부 null이고 오류가 아니다")
	void leavesExtremesNullWhenNoDailyCandleFallsInsideTheHold() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());
		LocalDateTime buyAt = LocalDateTime.of(SELL_DATE, LocalTime.of(9, 0));
		LocalDateTime sellAt = LocalDateTime.of(SELL_DATE, LocalTime.of(13, 0));

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, buyAt, sellAt);

		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighAt()).isNull();
		assertThat(response.sellVsHighRate()).isNull();
		assertThat(response.holdLowPrice()).isNull();
		assertThat(response.holdLowAt()).isNull();
		assertThat(response.sellVsLowRate()).isNull();
		assertThat(response.holdHighBasis()).isNull();
		assertThat(response.counterfactuals().atHoldHigh()).isNull();
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	@Test
	@DisplayName("1분봉이 없으면 postSellHighPrice·postSellHighAt이 일봉으로 채워지지 않고 null이다")
	void neverFillsPostSellHighFromDailyCandles() {
		givenMinuteCandles(List.of());
		givenDailyCandles(dailyCandles());
		LocalDateTime sellAt = LocalDateTime.of(SELL_DATE, LocalTime.of(21, 0));
		LocalDateTime buyAt = sellAt.minusMinutes(30);

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, buyAt, sellAt);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.postSellFlow().postSellHighAt()).isNull();
		assertThat(response.postSellFlow().closePrice()).isNotNull();
	}

	@Test
	@DisplayName("카드 시점의 1분봉이 없으면 atFirstMoveAfterBuy가 일봉으로 채워지지 않고 null이다")
	void neverFillsFirstMoveScenarioFromDailyCandles() {
		givenMinuteCandles(List.of());
		givenDailyCandles(dailyCandles());
		givenHeldCard(LocalDateTime.of(SELL_DATE, LocalTime.of(1, 0)));

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.priceMoves()).hasSize(1);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().atFirstMoveAfterBuy()).isNull();
	}

	@Test
	@DisplayName("카드 시점의 1분봉이 있으면 그 분의 close로 atFirstMoveAfterBuy가 채워진다")
	void fillsFirstMoveScenarioFromTheCandleAtThatMinute() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());
		givenHeldCard(LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30)));

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.counterfactuals().atFirstMoveAfterBuy()).isNotNull();
		assertThat(response.counterfactuals().atFirstMoveAfterBuy().price()).isEqualByComparingTo("70800");
		assertThat(response.counterfactuals().atFirstMoveAfterBuy().at())
			.isEqualTo(LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30)));
	}

	@Test
	@DisplayName("atClose가 매도일 일봉 close이고 closeAt·at이 그 일자 23:59다")
	void usesTheSellDayDailyCloseWithADayEndLabel() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		LocalDateTime dayEnd = LocalDateTime.of(SELL_DATE, LocalTime.of(23, 59));
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.postSellFlow().closeAt()).isEqualTo(dayEnd);
		assertThat(response.postSellFlow().sellToCloseRate()).isEqualByComparingTo("0.0102");
		assertThat(response.counterfactuals().atClose().price()).isEqualByComparingTo("69200");
		assertThat(response.counterfactuals().atClose().at()).isEqualTo(dayEnd);
	}

	@Test
	@DisplayName("매도일 일봉을 못 받으면 closePrice·closeAt·atClose가 null이면서 status는 READY다")
	void leavesCloseNullButStatusReadyWhenTheDailyCandleIsMissing() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(List.of());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().closeAt()).isNull();
		assertThat(response.postSellFlow().sellToCloseRate()).isNull();
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().atClose()).isNull();
	}

	@Test
	@DisplayName("sameSessionCompleted가 항상 true이고 buyAt·sellAt이 체결 시각 그대로다")
	void alwaysReportsSameSessionCompletedWithRawExecutionTimes() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.sameSessionCompleted()).isTrue();
		assertThat(response.buyAt()).isEqualTo(BUY_AT_199);
		assertThat(response.sellAt()).isEqualTo(SELL_AT);
	}

	@Test
	@DisplayName("반사실 returnRate가 코인 요율 0.0005로 계산된다 — 주식 0.00015 결과와 다르다")
	void usesTheCryptoFeeRateInCounterfactualReturnRates() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.counterfactuals().atClose().returnRate()).isEqualByComparingTo("-0.0121");
		assertThat(response.counterfactuals().atClose().returnRate())
			.as("주식 요율을 쓰면 예외도 로그도 없이 이 값이 나온다")
			.isNotEqualByComparingTo("-0.0117");

		assertThat(response.counterfactuals().atHoldHigh().returnRate()).isEqualByComparingTo("0.0108");
	}

	@Test
	@DisplayName("보유 구간 이전 봉이 섞여 와도 극값·sellVsHighRate·atHoldHigh에 들어가지 않는다")
	void excludesCandlesBeforeTheHoldWindowFromTheExtremes() {
		givenMinuteCandlesIgnoringLowerBound(withCandleBeforeTheHold(minuteCandles(), "99999"));
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.holdHighPrice())
			.as("보유 시작 이전 봉이 최고가로 나가면 사용자가 가질 수 없었던 가격으로 후회를 유도한다")
			.isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30)));
		assertThat(response.sellVsHighRate()).isEqualByComparingTo("-0.0325");
		assertThat(response.counterfactuals().atHoldHigh().price()).isEqualByComparingTo("70800");
	}

	@Test
	@DisplayName("매도 이전 봉이 섞여 와도 postSellHighPrice에 들어가지 않는다")
	void excludesCandlesBeforeTheSellMinuteFromThePostSellHigh() {
		LocalDateTime buyAt = LocalDateTime.of(SELL_DATE, LocalTime.of(20, 0));
		LocalDateTime sellAt = LocalDateTime.of(SELL_DATE, LocalTime.of(21, 0));
		givenMinuteCandlesIgnoringLowerBound(List.of(
			candle(LocalDateTime.of(SELL_DATE, LocalTime.of(20, 30)), "99999"),
			candle(sellAt, "68500"),
			candle(LocalDateTime.of(SELL_DATE, LocalTime.of(22, 0)), "70000")));
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, buyAt, sellAt);

		assertThat(response.postSellFlow().postSellHighPrice())
			.as("매도 이전 봉이 '매도 후 최고가'로 나가면 배타 경계의 근거가 무너진다")
			.isEqualByComparingTo("70000");
		assertThat(response.postSellFlow().postSellHighAt())
			.isEqualTo(LocalDateTime.of(SELL_DATE, LocalTime.of(22, 0)));
		assertThat(response.holdHighPrice()).isEqualByComparingTo("99999");
	}

	@Test
	@DisplayName("공급자 장애(MARKET_DATA_PROVIDER_ERROR)면 가격만 비고 status는 게이트대로 READY다")
	void absorbsProviderFailuresIntoNullPricesWithoutFailingTheWholeRead() {
		givenCandlesFailingWith(ErrorCode.MARKET_DATA_PROVIDER_ERROR);

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.tradeId()).isEqualTo(SELL_TRADE_ID);
		assertThat(response.sellPrice()).isEqualByComparingTo(SELL_PRICE);
		assertThat(response.holdingMinutes()).isEqualTo(199);

		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighBasis()).isNull();
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.counterfactuals().atClose()).isNull();
		assertThat(response.counterfactuals().atHoldHigh()).isNull();

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	@Test
	@DisplayName("공급자 장애가 아닌 오류는 흡수하지 않고 그대로 올린다")
	void neverAbsorbsErrorCodesOtherThanProviderFailure() {
		givenCandlesFailingWith(ErrorCode.VALIDATION_ERROR);

		assertThatThrownBy(() -> read(GATE_OPENS_AT, BUY_AT_199))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	@DisplayName("카드 조회 → 캔들 REST → 집단 비교 순서로 부르고 REST 구간이 DB 조회 둘 사이에 들어간다")
	void readsCardsBeforeTheRestCallsAndPeerComparisonAfterThem() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());
		List<HeldPriceMoveItem> priceMoves = givenHeldCard(LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30)));

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		InOrder inOrder = inOrder(cryptoPostSellFeedbackDbReader, candleQueryService);
		inOrder.verify(cryptoPostSellFeedbackDbReader).findHeldPriceMoves(any(), eq(BUY_AT_199), eq(SELL_AT));
		inOrder.verify(candleQueryService, atLeastOnce()).getCryptoCandles(eq(SYMBOL), any(), any(), any());
		inOrder.verify(cryptoPostSellFeedbackDbReader).buildPeerComparison(priceMoves);

		assertThat(response.priceMoves()).isEqualTo(priceMoves);
		assertThat(response.peerComparison()).isSameAs(PEER_COMPARISON);
	}

	@Test
	@DisplayName("CryptoPostSellFeedbackReader에는 클래스·read 어디에도 @Transactional이 없다")
	void neverWrapsTheCryptoAssemblyInATransaction() throws Exception {
		assertThat(CryptoPostSellFeedbackReader.class.getAnnotation(Transactional.class)).isNull();
		assertThat(CryptoPostSellFeedbackReader.class.getAnnotation(jakarta.transaction.Transactional.class))
			.isNull();

		Method read = CryptoPostSellFeedbackReader.class.getDeclaredMethod(
			"read", Trade.class, SellAllocationSummaryDto.class);
		assertThat(read.getAnnotation(Transactional.class)).isNull();
		assertThat(read.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
	}

	private PostSellFeedbackResponse read(LocalDateTime now, LocalDateTime buyAt) {
		return read(now, buyAt, SELL_AT);
	}

	private PostSellFeedbackResponse read(LocalDateTime now, LocalDateTime buyAt, LocalDateTime sellAt) {
		CryptoPostSellFeedbackReader reader = new CryptoPostSellFeedbackReader(
			candleQueryService, cryptoPostSellFeedbackDbReader, Clock.fixed(now.atZone(KST).toInstant(), KST));
		return reader.read(cryptoSellTrade(sellAt), allocation(buyAt));
	}

	private void givenMinuteCandles(List<CryptoCandleDto> candles) {
		when(candleQueryService.getCryptoCandles(eq(SYMBOL), eq(CandleInterval.ONE_MINUTE), any(), any()))
			.thenAnswer(invocation -> withinRange(
				candles, invocation.getArgument(2), invocation.getArgument(3)));
	}

	private void givenDailyCandles(List<CryptoCandleDto> candles) {
		when(candleQueryService.getCryptoCandles(eq(SYMBOL), eq(CandleInterval.ONE_DAY), any(), any()))
			.thenReturn(candles);
	}

	private void givenMinuteCandlesIgnoringLowerBound(List<CryptoCandleDto> candles) {
		when(candleQueryService.getCryptoCandles(eq(SYMBOL), eq(CandleInterval.ONE_MINUTE), any(), any()))
			.thenAnswer(invocation -> upTo(candles, invocation.getArgument(3)));
	}

	private void givenCandlesFailingWith(ErrorCode errorCode) {
		when(candleQueryService.getCryptoCandles(eq(SYMBOL), any(), any(), any()))
			.thenThrow(new BusinessException(errorCode));
	}

	private static List<CryptoCandleDto> withCandleBeforeTheHold(List<CryptoCandleDto> candles, String close) {
		List<CryptoCandleDto> withLeading = new ArrayList<>();
		withLeading.add(candle(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(22, 50)), close));
		withLeading.addAll(candles);
		return Collections.unmodifiableList(withLeading);
	}

	private static List<CryptoCandleDto> upTo(List<CryptoCandleDto> candles, LocalDateTime to) {
		List<CryptoCandleDto> filtered = new ArrayList<>();
		for (CryptoCandleDto candle : candles) {
			if (!candle.sourceTime().isAfter(to)) {
				filtered.add(candle);
			}
		}
		return Collections.unmodifiableList(filtered);
	}

	private static List<CryptoCandleDto> withinRange(
		List<CryptoCandleDto> candles, LocalDateTime from, LocalDateTime to) {
		List<CryptoCandleDto> filtered = new ArrayList<>();
		for (CryptoCandleDto candle : candles) {
			if (!candle.sourceTime().isBefore(from) && !candle.sourceTime().isAfter(to)) {
				filtered.add(candle);
			}
		}
		return Collections.unmodifiableList(filtered);
	}

	private static List<CryptoCandleDto> minuteCandles() {
		return List.of(
			candle(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 0)), "68200"),
			candle(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 1)), "68000"),
			candle(LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30)), "70800"),
			candle(LocalDateTime.of(SELL_DATE, LocalTime.of(2, 20)), "68500"));
	}

	private static List<CryptoCandleDto> dailyCandles() {
		return List.of(
			candle(SELL_DATE.minusDays(1).atStartOfDay(), "71000"),
			candle(SELL_DATE.atStartOfDay(), "69200"));
	}

	private static List<CryptoCandleDto> multiDayCandles() {
		return List.of(
			candle(SELL_DATE.minusDays(2).atStartOfDay(), "70000"),
			candle(SELL_DATE.minusDays(1).atStartOfDay(), "71000"),
			candle(SELL_DATE.atStartOfDay(), "90000"));
	}

	private static CryptoCandleDto candle(LocalDateTime sourceTime, String close) {
		BigDecimal closePrice = new BigDecimal(close);
		return new CryptoCandleDto(
			sourceTime, closePrice, closePrice.add(new BigDecimal("5000")),
			closePrice.subtract(new BigDecimal("5000")), closePrice, new BigDecimal("1.5"));
	}

	private List<HeldPriceMoveItem> givenHeldCard(LocalDateTime windowEnd) {
		List<HeldPriceMoveItem> priceMoves = List.of(new HeldPriceMoveItem(
			11L, windowEnd.minusMinutes(5), windowEnd, new BigDecimal("0.021"), 0, 0, "코인 카드", List.of()));
		when(cryptoPostSellFeedbackDbReader.findHeldPriceMoves(any(), any(), any())).thenReturn(priceMoves);
		return priceMoves;
	}

	private static SellAllocationSummaryDto allocation(LocalDateTime earliestBuyAt) {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			earliestBuyAt,
			null,
			ALLOCATED_COST,
			ALLOCATED_BUY_FEE,
			QUANTITY,
			Collections.singletonList(null));
	}

	private static Trade cryptoSellTrade(LocalDateTime executedAt) {
		Instrument instrument = cryptoInstrument();
		User user = User.create("crypto-trader@finplay.com", "password-hash", "ctrader", executedAt);
		Account account = Account.create(user, Market.CRYPTO, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, QUANTITY, "idem-key", "h".repeat(64),
			executedAt);
		Trade trade = Trade.of(
			order, account, instrument, null, OrderSide.SELL, SELL_PRICE, QUANTITY,
			685_000L, 342L, -15_447L, executedAt, executedAt);
		ReflectionTestUtils.setField(trade, "id", SELL_TRADE_ID);
		return trade;
	}

	private static Instrument cryptoInstrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, SYMBOL, "비트코인", new BigDecimal("1"), 5_000L, true, SELL_AT);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}
}
