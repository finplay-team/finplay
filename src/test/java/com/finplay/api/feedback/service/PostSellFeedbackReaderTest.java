// 매도 직후 피드백 조회 reader의 검증 순서·원장 수치 산출·buyAt 선정·sameSessionCompleted 판정을 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.service.SellAllocationQueryService;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 정본은 docs/api-contracts.md의 "매도 직후 피드백 조회" 소절과 spec 012 FEED-007이고, 이 파일이 보는 것은
// 이슈 #208 1번 항목이 소유한 완료 조건 다섯 중 셋이다 — 코인 400, 여러 lot의 buyAt, 서로 다른 원본 거래일이면
// sameSessionCompleted=false. 401·404·403·400의 HTTP 매핑은 PostSellFeedbackControllerTest가, 서술 생성·재사용은 PostSellFeedbackServiceTest가, 배분 요약의
// 실제 쿼리·가중평균은 SellAllocationQueryServiceTest가, 종단은 PostSellFeedbackIntegrationTest가 맡는다.
//
// 픽스처의 수치는 계약 예시 그대로다(매수원가 700,000 + 매수수수료 105, 매도 685,000 − 수수료 102,
// realizedPnl −15,207). 계약이 "값이 안 맞으면 예시가 아니라 구현이 틀린 것"이라고 적어 둔 자리다.
class PostSellFeedbackReaderTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	// 원본 거래일과 서비스 날짜를 다르게 둔다 — buyAt·sellAt을 trades.executed_at(서비스 벽시계) 그대로 쓴
	// 구현이면 날짜가 어긋나 여기서 빨개진다(계약 — "원본 거래일 기준 체결 시각").
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SELL_SERVICE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate EARLIEST_BUY_SERVICE_DATE = LocalDate.of(2026, 8, 3);

	private static final LocalTime EARLIEST_BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime LATER_BUY_TIME = LocalTime.of(10, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);

	private final TradeService tradeService = mock(TradeService.class);

	private final SellAllocationQueryService sellAllocationQueryService = mock(SellAllocationQueryService.class);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	// 4번 항목(peerComparison 상태 판정)은 PostSellFeedbackPeerComparisonTest가 전담한다 — 이 파일은 이전 항목의
	// 완료 조건(검증 순서·수치·buyAt·sameSessionCompleted)만 보므로 stub하지 않고 Mockito 기본값
	// (Optional.empty(), 곧 NOT_YET)으로 둔다.
	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	// 파생 사실(2번 항목)이 카드 노출 게이트에 그 체결의 서비스 날짜를 쓰므로 시계가 필요하다. 이 파일이 보는
	// 완료 조건은 게이트가 아니라 검증 순서·수치·buyAt·sameSessionCompleted라, 매도 서비스 날짜의 장중 시각으로
	// 고정해 게이트가 판정을 가리지 않게 둔다 — 게이트 자체(⑮)는 통합 테스트가 고정 Clock으로 본다.
	// 이 파일은 주식 조립만 본다 — 코인 조립의 내용은 CryptoPostSellFeedbackReaderTest가 맡고, 여기서는
	// "코인 체결이 이쪽으로 넘어간다"는 위임만 확인한다(이슈 #275).
	private final CryptoPostSellFeedbackReader cryptoPostSellFeedbackReader = mock(
		CryptoPostSellFeedbackReader.class);

	private final PostSellFeedbackReader postSellFeedbackReader = new PostSellFeedbackReader(
		cryptoPostSellFeedbackReader,
		tradeService,
		sellAllocationQueryService,
		stockReplayService,
		priceMoveEventRepository,
		new PriceMoveSourceLoader(priceMoveEventSourceRepository),
		priceMovePeerStatRepository,
		Clock.fixed(SELL_SERVICE_DATE.atTime(SELL_TIME).atZone(KST).toInstant(), KST));

	// --- 원장 수치 ---

	@Test
	@DisplayName("원장 수치를 계약 예시 그대로 돌려준다 — returnRate는 scale 4 HALF_UP이다")
	void returnsLedgerNumbersExactlyAsTheContractExample() {
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		givenAllocation(twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		assertThat(response.tradeId()).isEqualTo(SELL_TRADE_ID);
		assertThat(response.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.name()).isEqualTo("삼성전자");
		assertThat(response.buyPrice()).isEqualByComparingTo("70000");
		assertThat(response.sellPrice()).isEqualByComparingTo("68500");
		assertThat(response.quantity()).isEqualByComparingTo("10");
		assertThat(response.fee()).isEqualTo(102L);
		assertThat(response.realizedPnl()).isEqualTo(-15_207L);
		// −15,207 ÷ (700,000 + 105) = −0.02172102… → scale 4 HALF_UP. scale까지 고정한다.
		assertThat(response.returnRate()).isEqualTo(new BigDecimal("-0.0217"));
	}

	// 배분 원가 합만 쓰고 매수수수료를 분모에서 빼먹으면 −15,207 ÷ 700,000 = −0.0217(같은 값)이 되어 이 단정만
	// 으로는 안 잡힌다. 그래서 수수료가 분모에 실제로 들어가는지를 값이 갈리는 픽스처로 따로 본다.
	@Test
	@DisplayName("returnRate 분모에 배분된 매수수수료가 들어간다")
	void returnRateDenominatorIncludesAllocatedBuyFee() {
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE, -10_000L));
		// 원가 100,000 + 수수료 10,000 = 110,000. 수수료를 빼먹으면 −0.1000이 되고 포함하면 −0.0909다.
		givenAllocation(new SellAllocationSummaryDto(
			new BigDecimal("10000.00000000"),
			LocalDateTime.of(EARLIEST_BUY_SERVICE_DATE, EARLIEST_BUY_TIME),
			ORIGIN_TRADE_DATE,
			100_000L,
			10_000L,
			new BigDecimal("10"),
			List.of(ORIGIN_TRADE_DATE)));

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		assertThat(response.returnRate()).isEqualTo(new BigDecimal("-0.0909"));
		// 수수료를 분모에서 빼먹은 구현이 내는 답 — 두 답이 갈리는 픽스처임을 남긴다.
		assertThat(response.returnRate()).isNotEqualTo(new BigDecimal("-0.1000"));
	}

	// --- buyAt·sellAt·holdingMinutes ---

	@Test
	@DisplayName("buyAt은 배분된 두 lot 중 가장 이른 시각이고 날짜는 원본 거래일이다")
	void buyAtIsTheEarliestAllocatedLotOnTheOriginTradeDateAxis() {
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		SellAllocationSummaryDto allocation = twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE);
		givenAllocation(allocation);

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		// 픽스처 자기검증 — lot이 둘이고 시각이 다르므로 "나중 lot을 쓴 구현"은 10:30이 되어 이 단정에서 빨개진다.
		assertThat(allocation.buySourceTradingDates()).hasSize(2);
		assertThat(LATER_BUY_TIME).isNotEqualTo(EARLIEST_BUY_TIME);

		assertThat(response.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, EARLIEST_BUY_TIME));
		// 서비스 벽시계를 그대로 쓴 구현이 내는 답 — 실제 응답이 그것과 달라야 한다.
		assertThat(response.buyAt()).isNotEqualTo(allocation.earliestBuyAt());
		assertThat(response.sellAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(response.sellAt()).isNotEqualTo(LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
	}

	@Test
	@DisplayName("holdingMinutes는 응답의 buyAt~sellAt 사이다 — 09:30~14:40이면 310분이다")
	void holdingMinutesSpansTheTwoTimestampsInTheResponse() {
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		givenAllocation(twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		assertThat(response.holdingMinutes()).isEqualTo(310);
		// 나중 lot(10:30)을 기준으로 잰 구현이 내는 답 — 두 답이 갈리는 픽스처임을 남긴다.
		assertThat(response.holdingMinutes()).isNotEqualTo(250);
	}

	// 2026-08-04 결정 — 원본 거래일이 역전되면 holdingMinutes가 null이다(§파생 사실 계산). 같은 원본 거래일을
	// 여러 서비스 날짜에 재생할 수 있어 매도의 원본 거래일이 매수 lot보다 앞선 조합이 실제로 성립하고, 그때 음수가
	// 예외도 로그도 없이 나갔다. 아래 두 테스트는 짝이다 — 역전만 재현하면 "sameSessionCompleted=false면 전부
	// null"로 잘못 구현한 코드도 초록이기 때문이다.
	@Test
	@DisplayName("매도의 원본 거래일이 매수 lot보다 앞서면 holdingMinutes가 null이다 — 음수를 내지 않는다")
	void holdingMinutesIsNullWhenTheOriginTradeDatesAreReversed() {
		LocalDate laterOriginTradeDate = ORIGIN_TRADE_DATE.plusDays(1);
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		givenAllocation(twoLotSummary(laterOriginTradeDate, laterOriginTradeDate));

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		// 픽스처 자기검증 — 응답의 두 시각이 실제로 역전돼 있어야 이 규칙을 검증한다. 그대로 뺀 구현이 내는
		// 답이 음수이므로 두 구현이 갈린다(0으로 clamp한 구현도 이 단정에서 빨개진다).
		assertThat(response.sellAt()).isBefore(response.buyAt());
		assertThat(Duration.between(response.buyAt(), response.sellAt()).toMinutes()).isNegative();
		assertThat(response.holdingMinutes()).isNull();
	}

	@Test
	@DisplayName("원본 거래일이 순방향이면 sameSessionCompleted=false여도 holdingMinutes는 채워진다")
	void holdingMinutesStaysFilledForAForwardCrossSessionSell() {
		LocalDate earlierOriginTradeDate = ORIGIN_TRADE_DATE.minusDays(1);
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		givenAllocation(twoLotSummary(earlierOriginTradeDate, earlierOriginTradeDate));

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		// 계약의 sameSessionCompleted=false nullable 목록에 holdingMinutes는 없다 — 조건은 역전뿐이다.
		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.buyAt()).isEqualTo(LocalDateTime.of(earlierOriginTradeDate, EARLIEST_BUY_TIME));
		assertThat(response.holdingMinutes()).isEqualTo(1750);
	}

	// --- sameSessionCompleted ---

	@Test
	@DisplayName("배분 lot이 전부 매도와 같은 원본 거래일이면 sameSessionCompleted=true다")
	void sameSessionCompletedIsTrueWhenEveryAllocatedLotSharesTheSellOriginTradeDate() {
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		givenAllocation(twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		assertThat(response.sameSessionCompleted()).isTrue();
	}

	// 완료 조건 3번. 픽스처가 "가장 이른 lot만 보는 구현"에서 실제로 빨간지가 이 테스트의 핵심이다 —
	// 가장 이른 lot의 원본 거래일을 매도와 같게 두고, 나중 lot만 다른 거래일로 둔다. 두 lot이 같은 거래일인
	// 픽스처로는 두 구현이 같은 답을 내므로 회귀를 못 잡는다(tasks.md 1번).
	@Test
	@DisplayName("나중 lot만 원본 거래일이 다르면 sameSessionCompleted=false다 — 가장 이른 lot만 보는 구현은 여기서 true를 낸다")
	void sameSessionCompletedIsFalseWhenOnlyALaterLotHasADifferentOriginTradeDate() {
		LocalDate otherOriginTradeDate = LocalDate.of(2026, 7, 30);
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		SellAllocationSummaryDto allocation = twoLotSummary(ORIGIN_TRADE_DATE, otherOriginTradeDate);
		givenAllocation(allocation);

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		// 틀린 구현이 내는 답을 테스트 안에서 재현한다 — 가장 이른 lot만 대조하면 true다.
		boolean earliestLotOnlyVerdict = ORIGIN_TRADE_DATE.equals(allocation.earliestBuySourceTradingDate());
		assertThat(earliestLotOnlyVerdict).isTrue();
		assertThat(allocation.buySourceTradingDates()).contains(otherOriginTradeDate);

		// 올바른 구현은 배분된 lot 전부를 보므로 false다.
		assertThat(response.sameSessionCompleted()).isFalse();
	}

	// 2026-08-05 결정 — 같은 원본 거래일을 두 서비스 날짜에 재생하면 첫 재생일 오후에 매수하고 다음 재생일
	// 오전에 매도할 수 있어 **원본 거래일은 같은 채로 시각만 역전되는** 조합이 성립한다. 날짜만 대조하는 구현은
	// true를 내는데 보유 구간이 빈 구간이라 파생 사실이 전부 null로 나가 계약의 "true면 채워진다"와 어긋난다.
	@Test
	@DisplayName("원본 거래일이 같아도 시각이 역전되면 sameSessionCompleted=false이고 파생 사실이 전부 빈다")
	void sameSessionCompletedIsFalseWhenTheTimesAreReversedWithinTheSameOriginTradeDate() {
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		SellAllocationSummaryDto allocation = reversedSameDateSummary();
		givenAllocation(allocation);

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		// 틀린 구현이 내는 답을 재현한다 — lot 원본 거래일이 전부 매도와 같아 날짜 대조만으로는 true다.
		boolean dateOnlyVerdict = allocation.buySourceTradingDates().stream().allMatch(ORIGIN_TRADE_DATE::equals);
		assertThat(dateOnlyVerdict).isTrue();
		assertThat(response.sellAt()).isBefore(response.buyAt());

		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.holdingMinutes()).isNull();
		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighAt()).isNull();
		assertThat(response.holdLowPrice()).isNull();
		assertThat(response.holdLowAt()).isNull();
		assertThat(response.sellVsHighRate()).isNull();
		assertThat(response.sellVsLowRate()).isNull();
		assertThat(response.buyToNewsMinutes()).isNull();
		assertThat(response.priceMoves()).isEmpty();
		assertThat(response.postSellFlow()).isNull();
		assertThat(response.counterfactuals()).isNull();
		assertThat(response.peerComparison()).isNull();
		// 계산이 성립하지 않는 조합이라 분봉·카드를 읽지도 않는다.
		verifyNoInteractions(stockReplayService, priceMoveEventRepository, priceMoveEventSourceRepository);
	}

	// --- 아직 채우지 않는 필드 ---

	@Test
	@DisplayName("아직 채우지 않는 필드는 계약의 필드 집합을 유지한 채 null·[]이다")
	void leavesFieldsOwnedByLaterItemsAsNullOrEmptyList() {
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		givenAllocation(twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighAt()).isNull();
		assertThat(response.holdLowPrice()).isNull();
		assertThat(response.holdLowAt()).isNull();
		assertThat(response.sellVsHighRate()).isNull();
		assertThat(response.sellVsLowRate()).isNull();
		assertThat(response.buyToNewsMinutes()).isNull();
		assertThat(response.priceMoves()).isEmpty();
		// 매도 후 흐름·반사실은 이 픽스처가 장 마감 전(14:40) 조회라 게이트가 닫혀 있어 NOT_YET 껍데기다.
		// 게이트 자체는 PostSellFeedbackPostSellFlowTest가 본다. 집단 비교는 4번 항목부터 마감 게이트가 아니라
		// 확정 집계 행 존재로 판정한다(§C-4) — priceMoveEventRepository를 stub하지 않아 보유 구간 카드가
		// 0건이므로 NO_EVENT가 1순위로 판정된다. 상태값 4가지 분기는 PostSellFeedbackPeerComparisonTest가 본다.
		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		// 남은 것은 4번 항목(AI 서술) 몫이다.
		assertThat(response.narrative()).isNull();
		assertThat(response.narrativeSource()).isNull();
		assertThat(response.narrativeStatus()).isNull();
	}

	// --- 검증 순서 ---

	@Test
	@DisplayName("매수 체결이면 400 VALIDATION_ERROR이고 배분을 읽지 않는다")
	void rejectsBuyTradeWithValidationErrorWithoutReadingAllocations() {
		givenOwnedSellTrade(buyTrade());

		assertThatThrownBy(() -> postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(sellAllocationQueryService);
	}

	// 이슈 #275가 이 자리를 뒤집었다 — 코인 매도 체결은 더 이상 400이 아니라 200이고, 조립은 코인 전담
	// 컴포넌트가 맡는다(§FEED-012). 검증과 배분 조회는 여기 그대로 남아 두 시장이 같은 순서를 탄다.
	@Test
	@DisplayName("코인 매도 체결은 400이 아니라 배분을 읽고 코인 조립에 위임한다")
	void delegatesCryptoSellTradeToTheCryptoReaderInsteadOfRejectingIt() {
		Trade cryptoTrade = cryptoSellTrade();
		givenOwnedSellTrade(cryptoTrade);
		SellAllocationSummaryDto allocation = twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE);
		givenAllocation(allocation);

		postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		verify(sellAllocationQueryService).getSellAllocationSummary(SELL_TRADE_ID);
		verify(cryptoPostSellFeedbackReader).read(cryptoTrade, allocation);
		// 주식 조립 경로로 새지 않는다 — 코인에는 재생세션이 없어 그쪽으로 가면 그 자리에서 터진다.
		verifyNoInteractions(stockReplayService);
	}

	@Test
	@DisplayName("체결이 없으면 404 NOT_FOUND가 그대로 전파되고 배분을 읽지 않는다")
	void propagatesNotFoundWithoutReadingAllocations() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verifyNoInteractions(sellAllocationQueryService);
	}

	@Test
	@DisplayName("타인 체결이면 403 FORBIDDEN이 그대로 전파되고 배분을 읽지 않는다")
	void propagatesForbiddenWithoutReadingAllocations() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));

		verifyNoInteractions(sellAllocationQueryService);
	}

	// 검증 순서 확인 — 타인 소유의 코인 매수 체결은 getOwnedTrade 단계에서 403으로 끝난다. side·market 검사(400)에
	// 먼저 닿는 구현이면 400이 나와 이 테스트가 순서 위반을 드러낸다.
	@Test
	@DisplayName("타인 소유의 코인 매수 체결은 400이 아니라 403이다")
	void returnsForbiddenNotValidationErrorForOtherUsersCryptoBuyTrade() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN)
				.isNotEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	@DisplayName("배분은 그 매도 체결 id로만 조회한다")
	void readsAllocationsOfTheRequestedSellTradeOnly() {
		givenOwnedSellTrade(sellTrade(ORIGIN_TRADE_DATE));
		givenAllocation(twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		verify(tradeService).getOwnedTrade(USER_ID, SELL_TRADE_ID);
		verify(sellAllocationQueryService).getSellAllocationSummary(SELL_TRADE_ID);
	}

	// --- 픽스처 ---

	private void givenOwnedSellTrade(Trade trade) {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
	}

	private void givenAllocation(SellAllocationSummaryDto allocation) {
		when(sellAllocationQueryService.getSellAllocationSummary(any())).thenReturn(allocation);
	}

	/**
	 * 계약 예시의 배분 요약. lot이 <b>둘</b>이고(완료 조건 2번이 요구한다) 가장 이른 lot은 09:30, 나중 lot은
	 * 10:30이다. 가장 이른 lot의 벽시계 날짜를 매도의 서비스 날짜와 다르게 둬서, 그 값을 그대로 buyAt으로 쓴
	 * 구현이 드러나게 한다.
	 */
	private static SellAllocationSummaryDto twoLotSummary(
		LocalDate earliestLotOriginTradeDate, LocalDate laterLotOriginTradeDate) {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(EARLIEST_BUY_SERVICE_DATE, EARLIEST_BUY_TIME),
			earliestLotOriginTradeDate,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(earliestLotOriginTradeDate, laterLotOriginTradeDate));
	}

	/**
	 * 원본 거래일은 매도와 같지만 매수 시각이 매도보다 <b>늦은</b> 배분 요약 — 같은 원본 거래일을 두 서비스
	 * 날짜에 재생했을 때 성립하는 조합이다(첫 재생일 15:10 매수 → 다음 재생일 14:40 매도).
	 */
	private static SellAllocationSummaryDto reversedSameDateSummary() {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(EARLIEST_BUY_SERVICE_DATE, LocalTime.of(15, 10)),
			ORIGIN_TRADE_DATE,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));
	}

	private static Trade sellTrade(LocalDate originTradeDate) {
		return sellTrade(originTradeDate, -15_207L);
	}

	private static Trade sellTrade(LocalDate originTradeDate, Long realizedPnl) {
		Instrument instrument = stockInstrument();
		return trade(
			instrument,
			session(SELL_SERVICE_DATE, originTradeDate),
			OrderSide.SELL,
			new BigDecimal("68500"),
			685_000L,
			102L,
			realizedPnl,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
	}

	private static Trade buyTrade() {
		Instrument instrument = stockInstrument();
		return trade(
			instrument,
			session(SELL_SERVICE_DATE, ORIGIN_TRADE_DATE),
			OrderSide.BUY,
			new BigDecimal("70000"),
			700_000L,
			105L,
			null,
			LocalDateTime.of(SELL_SERVICE_DATE, EARLIEST_BUY_TIME));
	}

	// 코인 체결에는 재생세션이 없다 (Trade가 그것을 강제한다).
	private static Trade cryptoSellTrade() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1"), 5_000L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		return trade(
			instrument,
			null,
			OrderSide.SELL,
			new BigDecimal("100000000"),
			100_000_000L,
			50_000L,
			1_000L,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
	}

	private static Trade trade(
		Instrument instrument,
		StockReplaySession session,
		OrderSide side,
		BigDecimal price,
		long amount,
		long fee,
		Long realizedPnl,
		LocalDateTime executedAt) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, com.finplay.api.account.domain.Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, side, OrderType.MARKET, new BigDecimal("10"), "idem-key", "h".repeat(64),
			executedAt);
		Trade trade = Trade.of(
			order, account, instrument, session, side, price, new BigDecimal("10"), amount, fee, realizedPnl,
			executedAt, executedAt);
		ReflectionTestUtils.setField(trade, "id", SELL_TRADE_ID);
		return trade;
	}

	private static Instrument stockInstrument() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}

	private static StockReplaySession session(LocalDate serviceDate, LocalDate sourceTradingDate) {
		LocalDateTime resolvedAt = LocalDateTime.of(serviceDate, LocalTime.of(8, 40));
		return StockReplaySession.ready(serviceDate, sourceTradingDate, resolvedAt, resolvedAt);
	}
}
