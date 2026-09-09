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
		saveCandle(FRIDAY, LocalTime.of(15, 30), "71200");
	}

	@AfterEach
	@Transactional
	void cleanUp() {
		if (createdUserId != null) {
			User user = userRepository.findById(createdUserId).orElseThrow();
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
		setClock(FRIDAY, LocalTime.of(23, 0));
		StockReplayPriceDto fridayOwnSessionQuote = stockReplayService.getCurrentPrice(instrument.getId());
		assertThat(fridayOwnSessionQuote.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(fridayOwnSessionQuote.sessionReady()).isTrue();
		assertThat(fridayOwnSessionQuote.replaySession()).isNotNull();
		assertThat(fridayOwnSessionQuote.sourceTradingDate()).isEqualTo(FRIDAY);
		assertThat(fridayOwnSessionQuote.price()).isEqualByComparingTo("71200");
		assertThat(fridayOwnSessionQuote.sourceTime()).isEqualTo(LocalDateTime.of(FRIDAY, LocalTime.of(15, 30)));

		setClock(SATURDAY, LocalTime.of(14, 0));
		StockReplayPriceDto saturdayQuote = stockReplayService.getCurrentPrice(instrument.getId());
		assertThat(saturdayQuote.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(saturdayQuote.price()).isEqualByComparingTo(fridayOwnSessionQuote.price());
		assertThat(saturdayQuote.sourceTime()).isEqualTo(fridayOwnSessionQuote.sourceTime());
		assertThat(saturdayQuote.sourceTradingDate()).isEqualTo(FRIDAY);
		assertThat(saturdayQuote.sessionReady()).isFalse();
		assertThat(saturdayQuote.replaySession()).isNull();

		List<StockCandleDto> saturdayCandles = stockReplayService.getRevealedCandles(instrument.getId(), null, null);
		assertThat(saturdayCandles).hasSize(5);
		assertThat(saturdayCandles).allSatisfy(c -> assertThat(c.tradingDate()).isEqualTo(FRIDAY));
		assertThat(saturdayCandles.get(saturdayCandles.size() - 1).close()).isEqualByComparingTo("71200");

		User user = createUser();
		createdUserId = user.getId();
		createAccount(user);
		assertThatThrownBy(
			() -> orderService.createOrder(user.getId(), "idem-hold-fallback-1", buyRequest(instrument.getId())))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.MARKET_CLOSED));

		setClock(MONDAY, LocalTime.of(8, 50));
		StockReplayPriceDto mondayBeforeOpenQuote = stockReplayService.getCurrentPrice(instrument.getId());
		assertThat(mondayBeforeOpenQuote.price()).isEqualByComparingTo(fridayOwnSessionQuote.price());
		assertThat(mondayBeforeOpenQuote.sourceTime()).isEqualTo(fridayOwnSessionQuote.sourceTime());
		assertThat(mondayBeforeOpenQuote.sourceTradingDate()).isEqualTo(FRIDAY);
		List<StockCandleDto> mondayBeforeOpenCandles = stockReplayService.getRevealedCandles(instrument.getId(), null,
			null);
		assertThat(mondayBeforeOpenCandles).hasSize(5);
		assertThat(mondayBeforeOpenCandles).allSatisfy(c -> assertThat(c.tradingDate()).isEqualTo(FRIDAY));

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

	@Test
	void closedFallbackAggregatedDailyCandlesRespectCursorUpperBoundAndSignalDataEnd() {
		setClock(SATURDAY, LocalTime.of(14, 0));
		KisHistoricalReplayPriceProvider stockPriceProvider = new KisHistoricalReplayPriceProvider(stockReplayService);
		CandleQueryService candleQueryService = new CandleQueryService(
			instrumentRepository, stockPriceProvider, new FakeCryptoCandleProvider());

		String cursorAfterFriday = CandleCursor.encode(LocalDateTime.of(SATURDAY, LocalTime.MIDNIGHT));
		CandleListResponse afterFriday = candleQueryService.getCandles(
			instrument.getId(), "1d", null, null, cursorAfterFriday);
		assertThat(afterFriday.content()).hasSize(1);
		assertThat(afterFriday.content().get(0).sourceTime()).isEqualTo(LocalDateTime.of(FRIDAY, LocalTime.MIDNIGHT));
		assertThat(afterFriday.hasNext()).isFalse();
		assertThat(afterFriday.nextCursor()).isNull();

		String cursorAtFriday = CandleCursor.encode(LocalDateTime.of(FRIDAY, LocalTime.MIDNIGHT));
		CandleListResponse beforeFriday = candleQueryService.getCandles(
			instrument.getId(), "1d", null, null, cursorAtFriday);
		assertThat(beforeFriday.content()).isEmpty();
		assertThat(beforeFriday.hasNext()).isFalse();
		assertThat(beforeFriday.nextCursor()).isNull();

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

		stockPriceStreamService.publishScheduledUpdates();
		String contentAfterFirstPublish = subscribeResult.getResponse().getContentAsString();
		assertThat(occurrences(contentAfterFirstPublish, frozenEventId)).isEqualTo(1);

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
