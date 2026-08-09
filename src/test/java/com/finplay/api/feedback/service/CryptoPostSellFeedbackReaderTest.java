// 코인 매도 회고 조립(게이트·200봉 정밀도 경계·일봉 부재·수수료율)을 CandleQueryService mock으로 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.domain.HoldHighBasis;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.CandleInterval;
import com.finplay.api.market.service.CandleQueryService;
import com.finplay.api.market.service.CryptoCandleDto;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

// tasks-275.md 4번 항목이다. 정본은 spec §FEED-012 결정 0~4·§C-5이고 계약은 docs/api-contracts.md의
// "코인 체결의 차이" 소절이다. 3adb8192가 구현한 CryptoPostSellFeedbackReader의 분기를 고정한다.
//
// CandleQueryService를 mock한다 — 200봉 상한은 공급자 계약이라 실제 호출로는 경계를 재현할 수 없고, 봉 유무를
// 케이스별로 만들어야 "일봉으로 채우지 않는다"를 보일 수 있다. mock만으로 끝내지 않는다: 게이트 전이는
// CryptoPostSellFeedbackGateIntegrationTest가 실제 원장 위에서 고정 Clock으로 본다(ADR-0003).
//
// 주식 테스트(PostSellFeedbackBoundaryIntegrationTest·PostSellFeedbackGateIntegrationTest)는 수정하지 않는다.
class CryptoPostSellFeedbackReaderTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String SYMBOL = "BTC";
	private static final Long INSTRUMENT_ID = 7L;
	private static final Long SELL_TRADE_ID = 2L;

	private static final LocalDate SELL_DATE = LocalDate.of(2026, 8, 5);

	// 체결 시각에 소수 초를 붙인다 — 정시 픽스처는 onMinuteBoundary가 있든 없든 같은 답을 내서 그 자리를
	// 검증하지 못한다(PostSellArithmetic.onMinuteBoundary의 경고).
	private static final LocalDateTime SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(2, 20, 41, 100_000_000));

	// 보유 199분 — 1분봉으로 덮을 수 있는 최대 구간이다(양 끝 포함 200봉).
	private static final LocalDateTime BUY_AT_199 = LocalDateTime.of(
		SELL_DATE.minusDays(1), LocalTime.of(23, 1, 17, 400_000_000));

	// 보유 200분 — 한 봉 넘겨 일봉 표본으로 내려가는 첫 값이다.
	private static final LocalDateTime BUY_AT_200 = LocalDateTime.of(
		SELL_DATE.minusDays(1), LocalTime.of(23, 0, 17, 400_000_000));

	private static final BigDecimal QUANTITY = new BigDecimal("10");
	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	// buyBasis = 배분 매수원가 700,000 + 배분 매수수수료 105 = 700,105.
	private static final long ALLOCATED_COST = 700_000L;
	private static final long ALLOCATED_BUY_FEE = 105L;

	// §C-5 게이트가 열리는 첫 순간 — 매도 체결 KST 날짜의 다음 날 00:00이다.
	private static final LocalDateTime GATE_OPENS_AT = SELL_DATE.plusDays(1).atStartOfDay();

	private final CandleQueryService candleQueryService = mock(CandleQueryService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	// 집단 비교의 저장·조회 키 정합은 CryptoPeerStatsBatchIntegrationTest가 실제 DB로 본다 — 여기서는
	// Mockito 기본값(Optional.empty())으로 두고 이 파일이 맡은 분기만 본다.
	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	private final FeedbackCryptoProperties cryptoProperties = new FeedbackCryptoProperties(
		30, 6, 5, 24, 100, 35, 45);

	// --- 게이트 (§C-5 · 결정 1) ---

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

		// 게이트가 가리는 것은 매도 후 구간뿐이다 — 보유 구간 극값은 매도 전 사실이라 그 전에도 채워진다.
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

	// 함정 재현 — 기준이 "오늘"이면 어제 판 체결이 오늘 다시 NOT_YET으로 되돌아간다. 같은 날 조회만 재현하면
	// 두 구현이 같은 답을 내므로, 이틀 뒤 조회까지 봐야 오늘 기준 구현이 드러난다.
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

	// --- 200봉 정밀도 경계 (결정 4) ---

	// 두 경계 케이스는 매수 시각이 1분 다를 뿐이다 — 그 1분이 표본과 값을 통째로 바꾼다.
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

		// 구간 경계는 분으로 내려 넘긴다 — 소수 초를 그대로 넘기면 매수 분봉이 하한 밖으로 밀린다.
		ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
		ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(candleQueryService)
			.getCryptoCandles(eq(SYMBOL), eq(CandleInterval.ONE_MINUTE), from.capture(), to.capture());
		assertThat(from.getValue()).isEqualTo(BUY_AT_199.withSecond(0).withNano(0));
		assertThat(to.getValue()).isEqualTo(SELL_AT.withSecond(0).withNano(0));
	}

	// 199분 케이스와 같은 1분봉을 seed해 둔다 — 200분에서도 1분봉을 쓰는 구현이면 70800이 나와 여기서 갈린다.
	@Test
	@DisplayName("보유 200분이면 holdHighBasis가 DAILY이고 극값이 일봉 close에서 나온다")
	void fallsBackToDailyCandlesAt200Minutes() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_200);

		assertThat(response.holdingMinutes()).isEqualTo(200);
		assertThat(response.holdHighBasis()).isEqualTo(HoldHighBasis.DAILY);
		// 매수일(08-04) 일봉 close. 1분봉을 그대로 쓰면 70800이 된다.
		assertThat(response.holdHighPrice()).isEqualByComparingTo("71000");
		assertThat(response.holdHighAt())
			.isEqualTo(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 59)));
	}

	// 매도일 일봉의 close는 매도 다음날 자정 값이라 보유하지 않은 구간의 가격이다 — 표본에 섞이면 근사가 아니라
	// 틀린 값이다. 이 픽스처는 매도일 일봉을 가장 높게 잡아, 섞이면 holdHighPrice가 그 값으로 바뀌게 해 뒀다.
	@Test
	@DisplayName("DAILY 표본은 매수일 ~ 매도 전날이고 매도일 일봉이 섞이지 않는다")
	void excludesTheSellDayCandleFromTheDailySample() {
		givenMinuteCandles(List.of());
		givenDailyCandles(multiDayCandles());
		LocalDateTime buyAt = LocalDateTime.of(SELL_DATE.minusDays(2), LocalTime.of(10, 0));
		LocalDateTime sellAt = LocalDateTime.of(SELL_DATE, LocalTime.of(12, 0));

		PostSellFeedbackResponse response = read(GATE_OPENS_AT.plusDays(1), buyAt, sellAt);

		assertThat(response.holdHighBasis()).isEqualTo(HoldHighBasis.DAILY);
		// 08-03=70000, 08-04=71000, 08-05(매도일)=90000. 매도일이 섞이면 90000이 최고가가 된다.
		assertThat(response.holdHighPrice())
			.as("매도일 일봉이 표본에 들어오면 보유하지 않은 구간의 가격이 최고가가 된다")
			.isEqualByComparingTo("71000");
		assertThat(response.holdHighAt())
			.isEqualTo(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 59)));
		assertThat(response.holdLowPrice()).isEqualByComparingTo("70000");

		// 같은 매도일 일봉이 atClose에는 쓰인다 — 제외 규칙이 "그 봉이 없다"가 아니라 "보유 구간 표본이 아니다"임을
		// 보인다. 두 자리를 함께 보지 않으면 봉을 통째로 못 받은 구현과 구분되지 않는다.
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("90000");
	}

	// --- 일봉 표본이 비는 경우 (§실패 처리) ---

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
		// 잰 값이 없는데 정밀도만 남으면 화면이 "근사값이 있다"로 읽는다.
		assertThat(response.holdHighBasis()).isNull();
		assertThat(response.counterfactuals().atHoldHigh()).isNull();
		// 오류가 아니라 게이트대로 READY다.
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	// --- 일봉 근사를 쓰지 않는 자리 (결정 4에서 가장 틀리기 쉬운 자리) ---

	@Test
	@DisplayName("1분봉이 없으면 postSellHighPrice·postSellHighAt이 일봉으로 채워지지 않고 null이다")
	void neverFillsPostSellHighFromDailyCandles() {
		givenMinuteCandles(List.of());
		givenDailyCandles(dailyCandles());
		// 매도 21:00 → 자정까지 178분이라 200봉 상한에는 걸리지 않는다. null의 이유가 "구간이 길어서"가 아니라
		// "그 구간 1분봉이 없어서"임을 이 시각이 보장한다.
		LocalDateTime sellAt = LocalDateTime.of(SELL_DATE, LocalTime.of(21, 0));
		LocalDateTime buyAt = sellAt.minusMinutes(30);

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, buyAt, sellAt);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.postSellFlow().postSellHighAt()).isNull();
		// 같은 일봉이 closePrice에는 쓰인다 — 일봉을 못 받은 것이 아니라 쓰지 않는 자리임을 보인다.
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

	// --- atClose (결정 2) ---

	@Test
	@DisplayName("atClose가 매도일 일봉 close이고 closeAt·at이 그 일자 23:59다")
	void usesTheSellDayDailyCloseWithADayEndLabel() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		LocalDateTime dayEnd = LocalDateTime.of(SELL_DATE, LocalTime.of(23, 59));
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.postSellFlow().closeAt()).isEqualTo(dayEnd);
		// sellToCloseRate = (종가 − 매도가) ÷ 매도가 = (69200 − 68500) ÷ 68500.
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

	// --- 결정 0 (재생 시간축 없음) ---

	@Test
	@DisplayName("sameSessionCompleted가 항상 true이고 buyAt·sellAt이 체결 시각 그대로다")
	void alwaysReportsSameSessionCompletedWithRawExecutionTimes() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.sameSessionCompleted()).isTrue();
		// 날짜를 갈아 끼우는 변환이 없다 — 소수 초까지 원장 값 그대로다.
		assertThat(response.buyAt()).isEqualTo(BUY_AT_199);
		assertThat(response.sellAt()).isEqualTo(SELL_AT);
	}

	// --- 수수료율 (결정 4 각주 · §반사실·집단 비교 계산) ---

	// 주식 요율을 쓰면 값이 조용히 어긋난다 — 두 요율의 결과를 나란히 놓아 대조가 보이게 한다.
	@Test
	@DisplayName("반사실 returnRate가 코인 요율 0.0005로 계산된다 — 주식 0.00015 결과와 다르다")
	void usesTheCryptoFeeRateInCounterfactualReturnRates() {
		givenMinuteCandles(minuteCandles());
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		// 종가 69,200 × 10주 = 692,000. 코인 수수료 692,000 × 0.0005 = 346 (FLOOR).
		// 실현손익 692,000 − 346 − 700,105 = −8,451 → −8,451 ÷ 700,105 = −0.0121 (scale 4 HALF_UP).
		assertThat(response.counterfactuals().atClose().returnRate()).isEqualByComparingTo("-0.0121");
		// 주식 요율(0.00015)이면 수수료 103 → 실현손익 −8,208 → −0.0117이다.
		assertThat(response.counterfactuals().atClose().returnRate())
			.as("주식 요율을 쓰면 예외도 로그도 없이 이 값이 나온다")
			.isNotEqualByComparingTo("-0.0117");

		// 보유 최고가 70,800 × 10 = 708,000, 수수료 354 → 708,000 − 354 − 700,105 = 7,541 → 0.0108.
		assertThat(response.counterfactuals().atHoldHigh().returnRate()).isEqualByComparingTo("0.0108");
	}

	// --- 공급자가 구간 밖 봉을 섞어 보낼 때 (5ad19e8d) ---

	// 운영 공급자 BithumbRestCandleProvider는 빗썸에 to와 count만 보내고 from을 하한으로 보내지 않는다 —
	// 시장에 빠진 분이 있으면 그 개수만큼 from 이전 봉이 따라온다. FakeCryptoCandleProvider는 구간으로 걸러
	// 주기 때문에 통합 테스트로는 이 자리가 영원히 드러나지 않는다. mock으로 직접 만들어야만 잡힌다.
	@Test
	@DisplayName("보유 구간 이전 봉이 섞여 와도 극값·sellVsHighRate·atHoldHigh에 들어가지 않는다")
	void excludesCandlesBeforeTheHoldWindowFromTheExtremes() {
		// 22:50은 매수(23:01)보다 이르다 — 보유하지 않은 구간의 가격이고, 걸러지지 않으면 최고가가 된다.
		givenMinuteCandlesIgnoringLowerBound(withCandleBeforeTheHold(minuteCandles(), "99999"));
		givenDailyCandles(dailyCandles());

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		assertThat(response.holdHighPrice())
			.as("보유 시작 이전 봉이 최고가로 나가면 사용자가 가질 수 없었던 가격으로 후회를 유도한다")
			.isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30)));
		// 70,800 기준 비율이다 — (68,500 − 70,800) ÷ 70,800. 99,999가 섞이면 값이 통째로 달라진다.
		assertThat(response.sellVsHighRate()).isEqualByComparingTo("-0.0325");
		assertThat(response.counterfactuals().atHoldHigh().price()).isEqualByComparingTo("70800");
	}

	// 매도 후 최고가의 하한(매도 분 + 1분)은 배타 경계다 — 구간 이전 봉이 섞이면 매도 "이전" 가격이
	// "매도 후 최고가"로 나가 보유 구간 극값과 나란히 놓는 의미가 사라진다.
	@Test
	@DisplayName("매도 이전 봉이 섞여 와도 postSellHighPrice에 들어가지 않는다")
	void excludesCandlesBeforeTheSellMinuteFromThePostSellHigh() {
		LocalDateTime buyAt = LocalDateTime.of(SELL_DATE, LocalTime.of(20, 0));
		LocalDateTime sellAt = LocalDateTime.of(SELL_DATE, LocalTime.of(21, 0));
		// 20:30은 보유 구간 안이지만 매도 후 구간([21:01, 23:59])에는 없다. 두 구간이 따로 걸러지는지를 본다.
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
		// 같은 봉이 보유 구간에는 정당하게 들어간다 — 필터가 두 구간에 각각 걸린다는 뜻이다.
		assertThat(response.holdHighPrice()).isEqualByComparingTo("99999");
	}

	// --- 공급자 장애 흡수 범위 (5ad19e8d) ---

	@Test
	@DisplayName("공급자 장애(MARKET_DATA_PROVIDER_ERROR)면 가격만 비고 status는 게이트대로 READY다")
	void absorbsProviderFailuresIntoNullPricesWithoutFailingTheWholeRead() {
		givenCandlesFailingWith(ErrorCode.MARKET_DATA_PROVIDER_ERROR);

		PostSellFeedbackResponse response = read(GATE_OPENS_AT, BUY_AT_199);

		// 원장 수치는 봉과 무관하므로 그대로 남는다 — 회고 전체가 사라지지 않는 것이 이 흡수의 목적이다.
		assertThat(response.tradeId()).isEqualTo(SELL_TRADE_ID);
		assertThat(response.sellPrice()).isEqualByComparingTo(SELL_PRICE);
		assertThat(response.holdingMinutes()).isEqualTo(199);

		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighBasis()).isNull();
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.counterfactuals().atClose()).isNull();
		assertThat(response.counterfactuals().atHoldHigh()).isNull();

		// 값을 못 구한 것과 아직 확정되지 않은 것은 다르다 — 게이트가 열렸으면 READY다(§C-4).
		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	// 흡수 범위가 넓어지면 진짜 결함이 조용히 빈 값으로 나간다 — 흡수 대상 하나만 단정하면 그 확장을 못 잡는다.
	@Test
	@DisplayName("공급자 장애가 아닌 오류는 흡수하지 않고 그대로 올린다")
	void neverAbsorbsErrorCodesOtherThanProviderFailure() {
		givenCandlesFailingWith(ErrorCode.VALIDATION_ERROR);

		assertThatThrownBy(() -> read(GATE_OPENS_AT, BUY_AT_199))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	// --- 픽스처 ---

	private PostSellFeedbackResponse read(LocalDateTime now, LocalDateTime buyAt) {
		return read(now, buyAt, SELL_AT);
	}

	private PostSellFeedbackResponse read(LocalDateTime now, LocalDateTime buyAt, LocalDateTime sellAt) {
		CryptoPostSellFeedbackReader reader = new CryptoPostSellFeedbackReader(
			candleQueryService,
			priceMoveEventRepository,
			new PriceMoveSourceLoader(priceMoveEventSourceRepository),
			priceMovePeerStatRepository,
			cryptoProperties,
			Clock.fixed(now.atZone(KST).toInstant(), KST));
		return reader.read(cryptoSellTrade(sellAt), allocation(buyAt));
	}

	/**
	 * 1분봉 stub — 실제 공급자처럼 요청 구간으로 잘라 돌려준다. 구간을 잘못 넘긴 구현은 봉을 못 받아 값이
	 * 비므로, 이 필터가 곧 "구간을 제대로 넘겼는가"의 검증이기도 하다.
	 */
	private void givenMinuteCandles(List<CryptoCandleDto> candles) {
		when(candleQueryService.getCryptoCandles(eq(SYMBOL), eq(CandleInterval.ONE_MINUTE), any(), any()))
			.thenAnswer(invocation -> withinRange(
				candles, invocation.getArgument(2), invocation.getArgument(3)));
	}

	/**
	 * 일봉 stub — <b>요청 구간으로 자르지 않고</b> seed한 전부를 돌려준다.
	 *
	 * <p>공급자가 구간을 정확히 지켜 준다고 가정하지 않는다(200봉 상한이 있어 실제로 지켜지지 않는다). 그래서
	 * 매도일 일봉을 걸러내는 것은 조회 구간이 아니라 {@code dailyCandlesWithinHold}의 필터여야 하고, 그 필터가
	 * 없어도 통과하는 테스트가 되지 않으려면 stub이 구간을 대신 걸러 주면 안 된다.
	 */
	private void givenDailyCandles(List<CryptoCandleDto> candles) {
		when(candleQueryService.getCryptoCandles(eq(SYMBOL), eq(CandleInterval.ONE_DAY), any(), any()))
			.thenReturn(candles);
	}

	/**
	 * 1분봉 stub — <b>{@code from}을 무시하고</b> {@code to} 이하 전부를 돌려준다.
	 *
	 * <p>운영 공급자를 그대로 모사한 것이다({@code BithumbRestCandleProvider}는 빗썸에 {@code to}와
	 * {@code count}만 보낸다). {@link #givenMinuteCandles}처럼 stub이 하한을 대신 걸러 주면 구현의 필터가
	 * 없어져도 테스트가 초록이라, 이 자리만큼은 stub이 걸러 주면 안 된다.
	 */
	private void givenMinuteCandlesIgnoringLowerBound(List<CryptoCandleDto> candles) {
		when(candleQueryService.getCryptoCandles(eq(SYMBOL), eq(CandleInterval.ONE_MINUTE), any(), any()))
			.thenAnswer(invocation -> upTo(candles, invocation.getArgument(3)));
	}

	// 봉 조회가 전부 실패하는 상황 — 간격·구간과 무관하게 같은 오류를 던진다.
	private void givenCandlesFailingWith(ErrorCode errorCode) {
		when(candleQueryService.getCryptoCandles(eq(SYMBOL), any(), any(), any()))
			.thenThrow(new BusinessException(errorCode));
	}

	// 매수(23:01)보다 이른 22:50 봉을 목록 맨 앞에 끼운다 — 공급자가 빠진 분만큼 앞쪽 봉을 얹어 보내는 상황이다.
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

	// 자정을 넘는 보유 구간의 1분봉. 최고가 70,800(08-05 00:30)·최저가 68,000(08-04 23:01)이다.
	private static List<CryptoCandleDto> minuteCandles() {
		return List.of(
			candle(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 0)), "68200"),
			candle(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 1)), "68000"),
			candle(LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30)), "70800"),
			candle(LocalDateTime.of(SELL_DATE, LocalTime.of(2, 20)), "68500"));
	}

	// 매수일(08-04)과 매도일(08-05)의 일봉.
	private static List<CryptoCandleDto> dailyCandles() {
		return List.of(
			candle(SELL_DATE.minusDays(1).atStartOfDay(), "71000"),
			candle(SELL_DATE.atStartOfDay(), "69200"));
	}

	// 사흘치 일봉 — 매도일(08-05)을 일부러 가장 높게 둬, 보유 구간 표본에 섞이면 최고가가 뒤바뀌게 한다.
	private static List<CryptoCandleDto> multiDayCandles() {
		return List.of(
			candle(SELL_DATE.minusDays(2).atStartOfDay(), "70000"),
			candle(SELL_DATE.minusDays(1).atStartOfDay(), "71000"),
			candle(SELL_DATE.atStartOfDay(), "90000"));
	}

	private static CryptoCandleDto candle(LocalDateTime sourceTime, String close) {
		BigDecimal closePrice = new BigDecimal(close);
		// high/low를 close보다 바깥에 둔다 — 극값이 close 기준인지 여기서 갈린다.
		return new CryptoCandleDto(
			sourceTime, closePrice, closePrice.add(new BigDecimal("5000")),
			closePrice.subtract(new BigDecimal("5000")), closePrice, new BigDecimal("1.5"));
	}

	private void givenHeldCard(LocalDateTime occurredAt) {
		PriceMoveEvent event = PriceMoveEvent.createCrypto(
			cryptoInstrument(), occurredAt, new BigDecimal("0.021"), new BigDecimal("3.0"),
			"코인 카드", NarrativeSource.TEMPLATE, occurredAt);
		ReflectionTestUtils.setField(event, "id", 11L);
		when(priceMoveEventRepository.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
			eq(INSTRUMENT_ID), eq(Market.CRYPTO), any(), any()))
			.thenReturn(List.of(event));
	}

	private static SellAllocationSummaryDto allocation(LocalDateTime earliestBuyAt) {
		// 코인 체결은 재생세션이 없어 원본 거래일이 null이다.
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			earliestBuyAt,
			null,
			ALLOCATED_COST,
			ALLOCATED_BUY_FEE,
			QUANTITY,
			Collections.singletonList(null));
	}

	// 코인 체결에는 재생세션이 없다 (Trade가 그것을 강제한다).
	private static Trade cryptoSellTrade(LocalDateTime executedAt) {
		Instrument instrument = cryptoInstrument();
		User user = User.create("crypto-trader@finplay.com", "password-hash", "ctrader", executedAt);
		Account account = Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, executedAt);
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
