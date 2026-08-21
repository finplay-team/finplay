// 금요일 장 마감 → 주말 → 월요일 09:01 전환까지 마지막 재생 상태 유지 폴백(spec 038, QUOTE-HOLD-*)이 실제
// MySQL·SSE 경로로 끝까지 이어지는지 검증하는 통합 테스트다 (ADR-0003 "핵심 시나리오는 Testcontainers 통합 테스트").
package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.dto.response.CandleListResponse;
import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Timeout(60)
class StockReplayHoldFallbackIntegrationTest {

	// holidays-2026.txt는 2026년 공휴일만 담고 있어 다른 연도 날짜와는 절대 겹치지 않는다. 이 저장소의 다른 어떤
	// 테스트도 2027년 날짜를 service_date로 쓰지 않는다(전수 검색 확인) — 공유 Testcontainers MySQL 컨테이너의
	// stock_replay_sessions.service_date UNIQUE 제약과 충돌하지 않는다.
	private static final LocalDate FRIDAY = LocalDate.of(2027, 7, 23);
	private static final LocalDate SATURDAY = LocalDate.of(2027, 7, 24);
	private static final LocalDate MONDAY = LocalDate.of(2027, 7, 26);

	@Autowired
	private TestClock clock;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockReplayService stockReplayService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private StockPriceStreamService stockPriceStreamService;

	@Autowired
	private SseEmitterRegistry sseEmitterRegistry;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	private Instrument instrument;
	private Long createdUserId;

	private void setClock(LocalDate date, LocalTime time) {
		clock.set(LocalDateTime.of(date, time));
	}

	// 금요일 재생세션을 READY로 확정하고, 그 원본 거래일(금요일)의 분봉을 하루 구간에 걸쳐 축약해 시딩한다
	// (391건 전체 대신 개장·중간·마감 구간을 대표하는 5건 — spec 038 tasks.md 5번이 허용하는 축약).
	@BeforeEach
	void seedFridaySessionAndCandles() {
		instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.STOCK, uniqueSymbol(), "홀드폴백테스트", BigDecimal.ONE, 0L, true, LocalDateTime.now()));
		stockReplaySessionRepository.saveAndFlush(StockReplaySession.ready(
			FRIDAY, FRIDAY, LocalDateTime.of(FRIDAY, LocalTime.of(8, 40)),
			LocalDateTime.of(FRIDAY, LocalTime.of(8, 0))));
		saveCandle(FRIDAY, LocalTime.of(9, 0), "70000");
		saveCandle(FRIDAY, LocalTime.of(9, 1), "70100");
		saveCandle(FRIDAY, LocalTime.of(12, 0), "70500");
		saveCandle(FRIDAY, LocalTime.of(15, 29), "71000");
		saveCandle(FRIDAY, LocalTime.of(15, 30), "71200"); // 금요일의 마지막 공개 분봉 — 폴백이 반환해야 할 기준값
	}

	@AfterEach
	@Transactional
	void cleanUp() {
		if (createdUserId != null) {
			User user = userRepository.findById(createdUserId).orElseThrow();
			// findAll()로 전체 테이블을 스캔한 뒤 계좌 ID로 필터링하지 않는다 — 공유 Testcontainers
			// MySQL(ADR-0003)에는 이 클래스 밖의 다른 테스트가 쌓아 둔 무관한 행이 훨씬 많고, 그 전부를
			// 프록시로 초기화하려다 LazyInitializationException·타임아웃이 나며 정리 자체가 실패했다
			// (실측 — 2026-08-17, PR #402/#405 CI에서 재현). 계좌 단위로 좁힌 조회만 쓴다.
			List<Account> accounts = accountRepository.findAllByUserId(createdUserId);
			for (Account account : accounts) {
				List<Holding> holdings = holdingRepository.findByAccountId(account.getId());
				List<Long> holdingIds = holdings.stream().map(Holding::getId).toList();
				if (!holdingIds.isEmpty()) {
					holdingLotRepository.deleteAll(holdingLotRepository.findByHoldingIdIn(holdingIds));
				}
				holdingRepository.deleteAll(holdings);
				List<com.finplay.api.domain.order.entity.Order> orders = orderRepository
					.findByAccountId(account.getId());
				for (var order : orders) {
					tradeRepository.findByOrderId(order.getId()).ifPresent(tradeRepository::delete);
				}
				orderRepository.deleteAll(orders);
			}
			accountRepository.deleteAll(accounts);
			userRepository.delete(user);
			createdUserId = null;
		}
		stockCandleRepository.deleteAll(
			stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrument.getId(), FRIDAY));
		stockCandleRepository.deleteAll(
			stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrument.getId(), MONDAY));
		stockReplaySessionRepository.findByServiceDate(MONDAY).ifPresent(stockReplaySessionRepository::delete);
		stockReplaySessionRepository.findByServiceDate(FRIDAY).ifPresent(stockReplaySessionRepository::delete);
		instrumentRepository.delete(instrument);
	}

	private void saveCandle(LocalDate tradingDate, LocalTime candleTime, String close) {
		stockCandleRepository.saveAndFlush(StockCandle.create(
			instrument, tradingDate, candleTime,
			new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close),
			100L, "TEST", LocalDateTime.now()));
	}

	private static String uniqueSymbol() {
		return "QH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
	}

	private User createUser() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(
			User.create("hold-fallback-" + suffix + "@finplay.com", "password-hash", "hold-fallback-" + suffix,
				LocalDateTime.now()));
	}

	private void createAccount(User user) {
		accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, LocalDateTime.now()));
	}

	private OrderCreateRequest buyRequest(Long instrumentId) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", BigDecimal.ONE);
	}

	@Test
	void weekendQuoteHoldsFridayCloseExactlyThenSwitchesToMondayReplayAtNineOhOne() {
		// 금요일 23:00 — 아직 금요일 자신의 세션(폴백이 아니다)에서 마지막으로 공개된 분봉값을 기준값으로 삼는다.
		setClock(FRIDAY, LocalTime.of(23, 0));
		StockReplayPriceDto fridayOwnSessionQuote = stockReplayService.getCurrentPrice(instrument.getId());
		assertThat(fridayOwnSessionQuote.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(fridayOwnSessionQuote.sessionReady()).isTrue();
		assertThat(fridayOwnSessionQuote.replaySession()).isNotNull();
		assertThat(fridayOwnSessionQuote.sourceTradingDate()).isEqualTo(FRIDAY);
		assertThat(fridayOwnSessionQuote.price()).isEqualByComparingTo("71200");
		assertThat(fridayOwnSessionQuote.sourceTime()).isEqualTo(LocalDateTime.of(FRIDAY, LocalTime.of(15, 30)));

		// 토요일 — 오늘 세션 행 자체가 없어 폴백이 필요하다. 값이 금요일 마지막에 보였던 값과 완전히 동일해야 한다(gap 없음).
		setClock(SATURDAY, LocalTime.of(14, 0));
		StockReplayPriceDto saturdayQuote = stockReplayService.getCurrentPrice(instrument.getId());
		assertThat(saturdayQuote.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(saturdayQuote.price()).isEqualByComparingTo(fridayOwnSessionQuote.price());
		assertThat(saturdayQuote.sourceTime()).isEqualTo(fridayOwnSessionQuote.sourceTime());
		assertThat(saturdayQuote.sourceTradingDate()).isEqualTo(FRIDAY);
		// 폴백 시세는 sessionReady=false·replaySession=null을 유지해 체결 경로에 구조적으로 도달할 수 없다(QUOTE-HOLD-005).
		assertThat(saturdayQuote.sessionReady()).isFalse();
		assertThat(saturdayQuote.replaySession()).isNull();

		List<StockCandleDto> saturdayCandles = stockReplayService.getRevealedCandles(instrument.getId(), null, null);
		assertThat(saturdayCandles).hasSize(5);
		assertThat(saturdayCandles).allSatisfy(c -> assertThat(c.tradingDate()).isEqualTo(FRIDAY));
		assertThat(saturdayCandles.get(saturdayCandles.size() - 1).close()).isEqualByComparingTo("71200");

		// 토요일에 주식 매수를 시도하면 지금처럼 409 MARKET_CLOSED다 — 값이 보인다고 주문이 열리지 않는다.
		User user = createUser();
		createdUserId = user.getId();
		createAccount(user);
		assertThatThrownBy(
			() -> orderService.createOrder(user.getId(), "idem-hold-fallback-1", buyRequest(instrument.getId())))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.MARKET_CLOSED));

		// 월요일 08:50 — 오늘(월요일) 세션 행이 아직 없다(세션 생성 배치는 08:40에 돌지만 이 테스트는 그 배치를
		// 흉내내지 않고 배치 이전 상태를 그대로 재현한다). 여전히 금요일 재생분 그대로다(QUOTE-HOLD-003).
		setClock(MONDAY, LocalTime.of(8, 50));
		StockReplayPriceDto mondayBeforeOpenQuote = stockReplayService.getCurrentPrice(instrument.getId());
		assertThat(mondayBeforeOpenQuote.price()).isEqualByComparingTo(fridayOwnSessionQuote.price());
		assertThat(mondayBeforeOpenQuote.sourceTime()).isEqualTo(fridayOwnSessionQuote.sourceTime());
		assertThat(mondayBeforeOpenQuote.sourceTradingDate()).isEqualTo(FRIDAY);
		List<StockCandleDto> mondayBeforeOpenCandles = stockReplayService.getRevealedCandles(instrument.getId(), null,
			null);
		assertThat(mondayBeforeOpenCandles).hasSize(5);
		assertThat(mondayBeforeOpenCandles).allSatisfy(c -> assertThat(c.tradingDate()).isEqualTo(FRIDAY));

		// 월요일 08:50, 세션이 READY로 먼저 확정된 뒤(배치가 08:40에 돈 상태) 분봉이 아직 하나도 안 들어온 경우도
		// 같은 결과여야 한다(QUOTE-HOLD-003 — "세션 행 없음"과 "세션은 READY인데 분봉 미공개"는 buildClosedMarketPrices
		// 안에서 같은 폴백 경로로 수렴하지만, 실제 DB로 이 경로도 한 번은 확인해 둔다). 오늘 세션의 원본 거래일(월요일)이
		// 아니라 여전히 폴백 거래일(금요일)이 반환되어야 한다.
		stockReplaySessionRepository.saveAndFlush(StockReplaySession.ready(
			MONDAY, MONDAY, LocalDateTime.of(MONDAY, LocalTime.of(8, 40)),
			LocalDateTime.of(MONDAY, LocalTime.of(8, 0))));
		StockReplayPriceDto mondayReadyNoCandleQuote = stockReplayService.getCurrentPrice(instrument.getId());
		assertThat(mondayReadyNoCandleQuote.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(mondayReadyNoCandleQuote.price()).isEqualByComparingTo(fridayOwnSessionQuote.price());
		assertThat(mondayReadyNoCandleQuote.sourceTime()).isEqualTo(fridayOwnSessionQuote.sourceTime());
		assertThat(mondayReadyNoCandleQuote.sourceTradingDate()).isEqualTo(FRIDAY);
		assertThat(mondayReadyNoCandleQuote.sessionReady()).isFalse();
		assertThat(mondayReadyNoCandleQuote.replaySession()).isNull();
		List<StockCandleDto> mondayReadyNoCandleCandles = stockReplayService.getRevealedCandles(instrument.getId(),
			null, null);
		assertThat(mondayReadyNoCandleCandles).hasSize(5);
		assertThat(mondayReadyNoCandleCandles).allSatisfy(c -> assertThat(c.tradingDate()).isEqualTo(FRIDAY));

		// 월요일 09:01 — 09:00 분봉이 공개되면 폴백에서 오늘(월요일) 재생분으로 전환된다.
		saveCandle(MONDAY, LocalTime.of(9, 0), "72000");
		setClock(MONDAY, LocalTime.of(9, 1));

		StockReplayPriceDto mondayOpenQuote = stockReplayService.getCurrentPrice(instrument.getId());
		assertThat(mondayOpenQuote.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(mondayOpenQuote.sessionReady()).isTrue();
		assertThat(mondayOpenQuote.replaySession()).isNotNull();
		assertThat(mondayOpenQuote.sourceTradingDate()).isEqualTo(MONDAY);
		assertThat(mondayOpenQuote.sourceTradingDate()).isNotEqualTo(FRIDAY);
		assertThat(mondayOpenQuote.price()).isEqualByComparingTo("72000");

		List<StockCandleDto> mondayOpenCandles = stockReplayService.getRevealedCandles(instrument.getId(), null, null);
		assertThat(mondayOpenCandles).hasSize(1);
		assertThat(mondayOpenCandles.get(0).tradingDate()).isEqualTo(MONDAY);
		assertThat(mondayOpenCandles.get(0).close()).isEqualByComparingTo("72000");
	}

	// PR #387 리뷰 권고 반영 — getRevealedAggregatedCandles의 CLOSED 폴백 분기(1d)는 이 PR 이전까지 실 MySQL로
	// 직접 검증된 적이 없었다. END_OF_DAY(LocalTime.MAX.withNano(0)) 컷오프가 실제 MySQL TIME 컬럼 비교에서
	// 정상 동작해 빈 배열이 아니라 폴백 거래일(금요일) 기준 실제 집계 결과가 나오는지 확인한다(1분봉 폴백 경로는
	// weekendQuoteHoldsFridayCloseExactlyThenSwitchesToMondayReplayAtNineOhOne이 이미 검증했다).
	@Test
	void weekendAggregatedDailyCandleFallbackReturnsFridaySessionFromRealMySql() {
		setClock(SATURDAY, LocalTime.of(14, 0));

		List<StockCandleDto> dailyCandles = stockReplayService.getRevealedAggregatedCandles(
			instrument.getId(), CandleInterval.ONE_DAY, null, null);

		assertThat(dailyCandles).hasSize(1);
		StockCandleDto fridayDaily = dailyCandles.get(0);
		assertThat(fridayDaily.tradingDate()).isEqualTo(FRIDAY);
		assertThat(fridayDaily.open()).isEqualByComparingTo("70000");
		assertThat(fridayDaily.high()).isEqualByComparingTo("71200");
		assertThat(fridayDaily.low()).isEqualByComparingTo("70000");
		assertThat(fridayDaily.close()).isEqualByComparingTo("71200");
		assertThat(fridayDaily.volume()).isEqualTo(500L);
	}

	// 048 항목5·plan §8-6: CLOSED 폴백 경로도 커서가 두 분기(오늘 세션·폴백)가 공유하는 buildAggregatedCandles의
	// toDate 인자로만 들어가므로, 폴백이 커서 상한을 넘는 봉을 만들어 낼 수 없다는 성질을 실 MySQL로 고정한다.
	// CandleQueryService를 CandleQueryServiceIntegrationTest와 같은 방식(직접 조립)으로 만들어, 이 클래스가 이미
	// TestClock으로 굴리고 있는 stockReplayService를 그대로 재사용한다 — 새 Instrument를 만들지 않고 @BeforeEach가
	// 시딩한 금요일 세션·분봉을 그대로 쓴다.
	@Test
	void closedFallbackAggregatedDailyCandlesRespectCursorUpperBoundAndSignalDataEnd() {
		setClock(SATURDAY, LocalTime.of(14, 0));
		KisHistoricalReplayPriceProvider stockPriceProvider = new KisHistoricalReplayPriceProvider(stockReplayService);
		CandleQueryService candleQueryService = new CandleQueryService(
			instrumentRepository, stockPriceProvider, new FakeCryptoCandleProvider());

		// 커서가 토요일 자정을 가리키면 상한이 금요일 23:59로 정규화되어, 폴백 거래일(금요일)의 일봉이 그대로 나온다.
		String cursorAfterFriday = CandleCursor.encode(LocalDateTime.of(SATURDAY, LocalTime.MIDNIGHT));
		CandleListResponse afterFriday = candleQueryService.getCandles(
			instrument.getId(), "1d", null, null, cursorAfterFriday);
		assertThat(afterFriday.content()).hasSize(1);
		assertThat(afterFriday.content().get(0).sourceTime()).isEqualTo(LocalDateTime.of(FRIDAY, LocalTime.MIDNIGHT));
		assertThat(afterFriday.hasNext()).isFalse();
		assertThat(afterFriday.nextCursor()).isNull();

		// 커서가 금요일 자정을 가리키면 상한이 목요일 23:59로 정규화되어 금요일 당일 집계봉조차 상한을 넘어가므로
		// 폴백이라도 새어 나가지 않고 데이터 끝(빈 content·hasNext=false)으로 판정된다(plan §8-6 핵심 주장).
		String cursorAtFriday = CandleCursor.encode(LocalDateTime.of(FRIDAY, LocalTime.MIDNIGHT));
		CandleListResponse beforeFriday = candleQueryService.getCandles(
			instrument.getId(), "1d", null, null, cursorAtFriday);
		assertThat(beforeFriday.content()).isEmpty();
		assertThat(beforeFriday.hasNext()).isFalse();
		assertThat(beforeFriday.nextCursor()).isNull();

		// 데이터 끝에서 같은 커서로 재요청해도 결과가 바뀌지 않는다 — 무한 루프 방지 확인.
		CandleListResponse repeated = candleQueryService.getCandles(
			instrument.getId(), "1d", null, null, cursorAtFriday);
		assertThat(repeated.content()).isEmpty();
		assertThat(repeated.hasNext()).isFalse();
	}

	@Test
	void sseSkipsRepeatedPriceEventsWhileFrozenAndSnapshotCarriesTheFrozenValue() throws Exception {
		setClock(SATURDAY, LocalTime.of(14, 0));

		User user = createUser();
		createdUserId = user.getId();
		createAccount(user);
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		MvcResult subscribeResult = mockMvc.perform(get("/api/stocks/stream")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(request().asyncStarted())
			.andReturn();
		assertThat(sseEmitterRegistry.getEmitters(Market.STOCK)).isNotEmpty();

		// 새로 구독한 클라이언트는 snapshot에 멈춘(금요일 마지막) 값을 그대로 받는다(QUOTE-HOLD-007).
		MarketSnapshotEvent snapshot = stockPriceStreamService.buildSnapshot();
		MarketSnapshotEvent.InstrumentPriceSnapshot mySnapshot = snapshot.prices().stream()
			.filter(price -> price.symbol().equals(instrument.getSymbol()))
			.findFirst()
			.orElseThrow();
		assertThat(mySnapshot.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(mySnapshot.price()).isEqualByComparingTo("71200");
		assertThat(mySnapshot.sourceTime()).isEqualTo(LocalDateTime.of(FRIDAY, LocalTime.of(15, 30)));
		String contentAfterSubscribe = subscribeResult.getResponse().getContentAsString();
		assertThat(contentAfterSubscribe).contains("event:snapshot");
		assertThat(contentAfterSubscribe).doesNotContain("event:price");

		String frozenEventId = "id:STOCK:" + instrument.getSymbol() + ":202707231530";

		// 매분 스케줄이 처음 도는 시점 — 이 심볼을 처음 관측하므로 최초 1회는 공개(reveal)로 취급돼 price 이벤트가 나간다.
		stockPriceStreamService.publishScheduledUpdates();
		String contentAfterFirstPublish = subscribeResult.getResponse().getContentAsString();
		assertThat(occurrences(contentAfterFirstPublish, frozenEventId)).isEqualTo(1);

		// 같은 토요일 안에서 시각을 조금 옮겨 스케줄을 다시 여러 번 돌려도 sourceTime이 바뀌지 않으므로 새 price
		// 이벤트가 나가지 않는다(QUOTE-HOLD-007).
		setClock(SATURDAY, LocalTime.of(14, 1));
		stockPriceStreamService.publishScheduledUpdates();
		setClock(SATURDAY, LocalTime.of(14, 2));
		stockPriceStreamService.publishScheduledUpdates();

		String contentAfterRepeatedPublish = subscribeResult.getResponse().getContentAsString();
		assertThat(occurrences(contentAfterRepeatedPublish, frozenEventId)).isEqualTo(1);
	}

	private static int occurrences(String content, String substring) {
		int count = 0;
		int index = 0;
		while ((index = content.indexOf(substring, index)) != -1) {
			count++;
			index += substring.length();
		}
		return count;
	}
}
