package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.OrderService;
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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class MarketDataPipelineIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private List<Instrument> realStockInstruments() {
		return instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK);
	}

	private Instrument realStockInstrument(int index) {
		return realStockInstruments().get(index);
	}

	private static final LocalDate TD_A = LocalDate.of(2026, 6, 9);
	private static final LocalDate SD_A = LocalDate.of(2026, 6, 10);

	private static final LocalDate TD_D = LocalDate.of(2026, 2, 2);
	private static final LocalDate SD_D = LocalDate.of(2026, 2, 3);

	private static final LocalDate TD_E = LocalDate.of(2026, 5, 6);
	private static final LocalDate SD_E = LocalDate.of(2026, 5, 7);

	@Autowired
	private TestClock clock;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private MarketDataImportRepository marketDataImportRepository;

	@Autowired
	private KisHistoricalCandleImportWriter importWriter;

	@Autowired
	private StockCollectionLock stockCollectionLock;

	@Autowired
	private StockReplaySessionScheduler stockReplaySessionScheduler;

	@Autowired
	private BusinessDayCalendar businessDayCalendar;

	@Autowired
	private PriceQueryService priceQueryService;

	@Autowired
	private StockPriceProvider stockPriceProvider;

	@Autowired
	private StockPriceStreamService stockPriceStreamService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private OrderRepository orderRepository;

	private Long pipelineUserId;

	private void setClock(LocalDate date, LocalTime time) {
		clock.set(LocalDateTime.of(date, time));
	}

	@AfterEach
	@Transactional
	void cleanUpDataCreatedByThisTest() {
		if (pipelineUserId != null) {
			User user = userRepository.findById(pipelineUserId).orElseThrow();
			List<Account> accounts = accountRepository.findAll().stream()
				.filter(account -> account.getUser().getId().equals(pipelineUserId)).toList();
			for (Account account : accounts) {
				var holdings = holdingRepository.findAll().stream()
					.filter(holding -> holding.getAccount().getId().equals(account.getId())).toList();
				for (var holding : holdings) {
					holdingLotRepository.deleteAll(holdingLotRepository.findAll().stream()
						.filter(lot -> lot.getHolding().getId().equals(holding.getId())).toList());
				}
				holdingRepository.deleteAll(holdings);
				var orders = orderRepository.findAll().stream()
					.filter(order -> order.getAccount().getId().equals(account.getId())).toList();
				for (var order : orders) {
					tradeRepository.findByOrderId(order.getId()).ifPresent(tradeRepository::delete);
				}
				orderRepository.deleteAll(orders);
			}
			accountRepository.deleteAll(accounts);
			userRepository.delete(user);
			pipelineUserId = null;
		}
		for (Instrument instrument : realStockInstruments()) {
			for (LocalDate tradingDate : List.of(TD_A, TD_D, TD_E)) {
				List<StockCandle> candles = stockCandleRepository
					.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrument.getId(), tradingDate);
				if (!candles.isEmpty()) {
					stockCandleRepository.deleteAll(candles);
				}
			}
		}
		for (LocalDate serviceDate : List.of(SD_A, SD_D, SD_E)) {
			stockReplaySessionRepository.findByServiceDate(serviceDate).ifPresent(stockReplaySessionRepository::delete);
		}
		for (LocalDate tradingDate : List.of(TD_A, TD_D, TD_E)) {
			List<MarketDataImport> imports = marketDataImportRepository
				.findBySourceTradingDateOrderByCollectedAtDesc(tradingDate);
			if (!imports.isEmpty()) {
				marketDataImportRepository.deleteAll(imports);
			}
		}
	}

	private static RawMinuteCandleDto candle(LocalTime time, String open, String high, String low, String close) {
		return new RawMinuteCandleDto(
			time, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 100L);
	}

	private KisHistoricalCandleCollector collectorWith(KisHistoricalCandleClient client) {
		return new KisHistoricalCandleCollector(
			instrumentRepository, client, stockCandleRepository, importWriter, clock, businessDayCalendar,
			stockCollectionLock);
	}

	private StockReplaySessionScheduler freshSchedulerInstanceAfterRestart() {
		return new StockReplaySessionScheduler(
			stockReplaySessionRepository, marketDataImportRepository, stockCandleRepository, clock,
			businessDayCalendar);
	}

	private static final class ThrowingKisHistoricalCandleClient implements KisHistoricalCandleClient {
		@Override
		public List<RawMinuteCandleDto> fetchMinuteCandles(String symbol, LocalDate tradingDate) {
			throw new IllegalStateException("응답 파싱 실패: 지원하지 않는 응답 구조");
		}
	}

	private static BigDecimal snapshotPriceFor(MarketSnapshotEvent snapshot, String symbol) {
		return snapshot.prices().stream()
			.filter(price -> price.symbol().equals(symbol))
			.findFirst()
			.orElseThrow()
			.price();
	}

	private User createUser(String scenario) {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(
			User.create(scenario + "-" + suffix + "@finplay.com", "password-hash", scenario + "-" + suffix,
				LocalDateTime.now()));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, LocalDateTime.now()));
	}

	private OrderCreateRequest buyRequest(Long instrumentId) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", BigDecimal.ONE);
	}

	@Test
	void collectThenScheduleThenReplay_revealsOpenThenCloseAndAgreesAcrossPriceApiSseAndOrderExecution() {
		Instrument instrument = realStockInstrument(0);

		setClock(SD_A, LocalTime.of(8, 10));
		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles(instrument.getSymbol(), List.of(
			candle(LocalTime.of(9, 0), "70000", "70200", "69900", "70100"),
			candle(LocalTime.of(9, 1), "70100", "70400", "70050", "70300")));
		collectorWith(fakeClient).collect();

		List<MarketDataImport> imports = marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(TD_A);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0).getStatus()).isNotEqualTo(ImportStatus.FAILED);
		if (imports.get(0).getFailureReason() != null) {
			assertThat(imports.get(0).getFailureReason()).doesNotContain(instrument.getSymbol());
		}
		assertThat(stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrument.getId(), TD_A))
			.hasSize(2);

		setClock(SD_A, LocalTime.of(8, 40));
		stockReplaySessionScheduler.resolveTodaySession();

		StockReplaySession session = stockReplaySessionRepository.findByServiceDate(SD_A).orElseThrow();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(TD_A);
		LocalDateTime firstResolvedAt = session.getResolvedAt();

		setClock(SD_A, LocalTime.of(9, 0, 30));
		PriceQuoteDto openQuote = priceQueryService.getPrice(instrument.getId());
		assertThat(openQuote.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(openQuote.price()).isEqualByComparingTo("70000");
		assertThat(openQuote.sourceTradingDate()).isEqualTo(TD_A);
		assertThat(stockPriceProvider.getMarketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(snapshotPriceFor(stockPriceStreamService.buildSnapshot(), instrument.getSymbol()))
			.isEqualByComparingTo("70000");

		setClock(SD_A, LocalTime.of(9, 2, 0));
		PriceQuoteDto closeQuote = priceQueryService.getPrice(instrument.getId());
		assertThat(closeQuote.price()).isEqualByComparingTo("70300");
		assertThat(snapshotPriceFor(stockPriceStreamService.buildSnapshot(), instrument.getSymbol()))
			.isEqualByComparingTo("70300");

		User user = createUser("pipeline");
		pipelineUserId = user.getId();
		createAccount(user);
		OrderResponse orderResponse = orderService.createOrder(
			user.getId(), "idem-pipeline-1", buyRequest(instrument.getId()));
		assertThat(orderResponse.price()).isEqualByComparingTo(closeQuote.price());
		assertThat(orderResponse.price()).isEqualByComparingTo("70300");

		setClock(SD_A, LocalTime.of(10, 0));
		freshSchedulerInstanceAfterRestart().resolveTodaySession();

		StockReplaySession afterRestart = stockReplaySessionRepository.findByServiceDate(SD_A).orElseThrow();
		assertThat(afterRestart.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(afterRestart.getSourceTradingDate()).isEqualTo(TD_A);
		assertThat(afterRestart.getResolvedAt()).isEqualTo(firstResolvedAt);

		assertThat(afterRestart.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(stockPriceProvider.getMarketStatus()).isEqualTo(StockMarketStatus.OPEN);
	}

	@Test
	void entireResponseErrorLeavesSessionFailedAndStockMarketClosed() {
		Instrument instrument = realStockInstrument(0);

		setClock(SD_D, LocalTime.of(8, 10));
		collectorWith(new ThrowingKisHistoricalCandleClient()).collect();

		List<MarketDataImport> imports = marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(TD_D);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0).getStatus()).isEqualTo(ImportStatus.FAILED);
		assertThat(stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrument.getId(), TD_D))
			.isEmpty();

		setClock(SD_D, LocalTime.of(8, 40));
		stockReplaySessionScheduler.resolveTodaySession();

		StockReplaySession session = stockReplaySessionRepository.findByServiceDate(SD_D).orElseThrow();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		assertThat(session.getSourceTradingDate()).isNull();
		assertThat(session.getFailureReason()).isNotBlank();

		setClock(SD_D, LocalTime.of(9, 30));
		assertThat(stockPriceProvider.getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThatThrownBy(() -> priceQueryService.getPrice(instrument.getId()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	@Test
	void singleInstrumentStructuralErrorStillOpensMarketAndPricesOtherInstruments() {
		Instrument goodInstrument = realStockInstrument(0);
		Instrument brokenInstrument = realStockInstrument(1);

		setClock(SD_E, LocalTime.of(8, 10));
		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles(goodInstrument.getSymbol(), List.of(
			candle(LocalTime.of(9, 0), "50000", "50200", "49900", "50100")));
		fakeClient.setCandles(brokenInstrument.getSymbol(), List.of(
			candle(LocalTime.of(9, 0), "10000", "10200", "9900", "10100"),
			candle(LocalTime.of(9, 0), "10000", "10200", "9900", "10100")));
		collectorWith(fakeClient).collect();

		List<MarketDataImport> imports = marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(TD_E);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0).getStatus()).isEqualTo(ImportStatus.PARTIAL_SUCCESS);
		assertThat(imports.get(0).getFailureReason()).contains(brokenInstrument.getSymbol());
		assertThat(
			stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(goodInstrument.getId(), TD_E))
			.hasSize(1);
		assertThat(
			stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(brokenInstrument.getId(), TD_E))
			.isEmpty();

		setClock(SD_E, LocalTime.of(8, 40));
		stockReplaySessionScheduler.resolveTodaySession();

		StockReplaySession session = stockReplaySessionRepository.findByServiceDate(SD_E).orElseThrow();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(TD_E);

		setClock(SD_E, LocalTime.of(9, 5));
		assertThat(stockPriceProvider.getMarketStatus()).isEqualTo(StockMarketStatus.OPEN);

		PriceQuoteDto goodQuote = priceQueryService.getPrice(goodInstrument.getId());
		assertThat(goodQuote.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(goodQuote.price()).isEqualByComparingTo("50100");

		assertThatThrownBy(() -> priceQueryService.getPrice(brokenInstrument.getId()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	@Test
	void preparationStatusEnumNeverRepresentsComputedOpenOrClosedMarketState() {
		assertThat(PreparationStatus.values())
			.containsExactlyInAnyOrder(PreparationStatus.PREPARING, PreparationStatus.READY, PreparationStatus.FAILED);
	}

}
