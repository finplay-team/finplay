// Fake KIS 응답으로 08:10 수집→08:40 세션 확정→09:00 재생까지 이슈 #19 파이프라인 전체를 실제 MySQL·Redis로 검증하는 통합 테스트다.
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

	// 이 테스트는 새 종목을 만들지 않고 마이그레이션이 시드한 실제 주식 16종을 그대로 재사용한다 — 두 가지 이유다.
	// ① KisHistoricalCandleCollector는 STOCK 종목 전체(instrumentRepository.findByMarketOrderByIdAsc)를 순회하며
	// 종목코드가 6자리 숫자인지부터 검사하므로, 새로 만든 종목이 남아 있으면 InstrumentRepositoryTest의 "정확히
	// 16+12종" 전역 카운트 검증이 깨진다. ② 시나리오 A는 실제 주문 체결(Holding·Trade)까지 만들어 FK가 얽히므로
	// 종목을 나중에 안전하게 지울 수도 없다. 실제 종목은 절대 지우지 않고, 이 테스트가 쓰는 거래일(TD_A·TD_D·TD_E)이
	// 다른 어떤 기존 테스트와도 겹치지 않게 골랐으므로 StockCandle UNIQUE 제약과도 충돌하지 않는다.
	private List<Instrument> realStockInstruments() {
		return instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK);
	}

	private Instrument realStockInstrument(int index) {
		return realStockInstruments().get(index);
	}

	// 시나리오 A(정상 파이프라인 — 수집→READY→재생→가격 조회→재시작→주문체결 동일 Provider 확인). 이슈 #19 항목 ⑦의 핵심 시나리오.
	// 기존 market 통합 테스트들이 2026년 7~8월 날짜를 이미 service_date(UNIQUE 제약)로 점유하고 있어(예:
	// CandleQueryServiceIntegrationTest), 겹치지 않는 6월 날짜를 쓴다.
	private static final LocalDate TD_A = LocalDate.of(2026, 6, 9); // 화
	private static final LocalDate SD_A = LocalDate.of(2026, 6, 10); // 수

	// 시나리오 D(전체 응답 오류 → FAILED·미개장). 다른 시나리오보다 훨씬 이른 날짜를 써서 StockReplaySessionScheduler의
	// 최대 30영업일 폴백 탐색이 다른 시나리오가 저장해 둔 성공 이력까지 거슬러 올라가 잘못 READY로 판정하지 않게 한다
	// (탐색은 항상 과거 방향으로만 진행되므로, 이 시나리오를 가장 이른 날짜에 두면 다른 시나리오의 데이터에 닿을 수 없다).
	// 이 날짜 선택은 spec 038(QUOTE-HOLD-006) 도입 이후 아래 entireResponseErrorLeavesSessionFailedAndStockMarketClosed의
	// PRICE_UNAVAILABLE 단정도 함께 지켜준다 — StockReplayService.findFallbackSession도 날짜 상한 없이 과거 전체를
	// 훑으므로, SD_D가 이 저장소 전체(공유 MySQL 컨테이너)에서 가장 이른 서비스 날짜가 아니면 다른 스펙이 커밋해 둔
	// READY 세션이 폴백 후보로 걸려 PRICE_UNAVAILABLE 대신 AVAILABLE이 나올 수 있다. 더 이른 날짜를 쓰는 통합 테스트를
	// 새로 추가하면 이 시나리오도 함께 재확인한다.
	private static final LocalDate TD_D = LocalDate.of(2026, 2, 2); // 월
	private static final LocalDate SD_D = LocalDate.of(2026, 2, 3); // 화

	// 시나리오 E(특정 종목만 구조 오류 → PARTIAL_SUCCESS, 나머지 종목 정상 개장).
	private static final LocalDate TD_E = LocalDate.of(2026, 5, 6); // 수
	private static final LocalDate SD_E = LocalDate.of(2026, 5, 7); // 목

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

	// 실제 Spring 빈 그대로 재사용한다 — collect()의 트랜잭션 경계 분리(PR #94 리뷰 권장사항 ②·④)가 실제 프록시를 통해
	// 동작하는지(자기 자신 호출로 인한 @Transactional 무력화 없이)까지 이 통합 테스트에서 함께 검증된다.
	@Autowired
	private KisHistoricalCandleImportWriter importWriter;

	// COLLECT-STAB-001 — 실제 Redis 배선의 락을 그대로 재사용한다. 이 클래스의 시나리오는 순차 호출(동시성 없음)이므로
	// 매번 새 거래일에 처음 tryLock하는 한 항상 획득에 성공하고, collect() 종료 시 finally에서 즉시 해제된다 — 동시
	// 경합 자체는 StockCollectionLockConcurrencyIntegrationTest가 별도로 검증한다.
	@Autowired
	private StockCollectionLock stockCollectionLock;

	// StockReplaySessionScheduler는 KisHistoricalCandleClient에 의존하지 않으므로(spec.md MKT-005), 실제 Spring 빈을
	// 그대로 재사용해도 안전하다 — 08:40 배치의 실제 배선을 검증하는 셈이다.
	@Autowired
	private StockReplaySessionScheduler stockReplaySessionScheduler;

	// 영업일 계산 공통화(PR #94 리뷰 권장) 이후 세 클래스가 공유하는 실제 Spring 빈을 그대로 재사용한다.
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

	// 이 테스트가 실제 종목에 남긴 분봉·재생세션·수집 이력을 정리한다 — 종목 자체는 만들지도 지우지도 않으므로 여기서는
	// 다루지 않는다. 다른 어떤 기존 테스트도 이 세 저장소에 전역 카운트(findAll·count) 단정을 걸지 않는 것을 확인했지만,
	// 반복 실행 시 데이터가 무한히 쌓이지 않도록 정리한다.
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

	// KisHistoricalCandleCollector는 실제 REST 구현체(KIS 키 필요)가 아니라 이 Fake로만 구동한다 — 08:10 수집 배치를
	// @Scheduled 우회하고 서비스 메서드를 직접 호출하는 방식(tasks.md 항목 ⑦ 지시)이다.
	private KisHistoricalCandleCollector collectorWith(KisHistoricalCandleClient client) {
		return new KisHistoricalCandleCollector(
			instrumentRepository, client, stockCandleRepository, importWriter, clock, businessDayCalendar,
			stockCollectionLock);
	}

	// "서버 재시작" 시뮬레이션 — StockReplaySessionScheduler는 인스턴스 상태가 없으므로, 새 인스턴스를 만들어 호출해도
	// 결과는 오직 DB 상태에만 의존한다. 실제 프로세스 재시작 대신 같은 ApplicationContext 안에서 이 방식으로 대체한다.
	private StockReplaySessionScheduler freshSchedulerInstanceAfterRestart() {
		return new StockReplaySessionScheduler(
			stockReplaySessionRepository, marketDataImportRepository, stockCandleRepository, clock,
			businessDayCalendar);
	}

	// KisHistoricalCandleClient가 모든 종목 호출에서 예외를 던지는 상황을 흉내낸다 — 각 종목 호출 실패는 그 종목 하나만의
	// 실패로 흡수되지만(PR #94 리뷰 권장사항 ③), 대상 종목 전부가 실패하면 결과적으로 FAILED로 기록된다.
	// FakeKisHistoricalCandleClient는 실패를 표현할 수 없으므로(항상 정상 리스트 반환) 이 시나리오 전용 더블을 따로 둔다
	// (KisHistoricalCandleCollectorTest와 동일한 관례).
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

		// 08:10 KST — Fake KIS 응답으로 직전 영업일(TD_A) 분봉 수집.
		setClock(SD_A, LocalTime.of(8, 10));
		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles(instrument.getSymbol(), List.of(
			candle(LocalTime.of(9, 0), "70000", "70200", "69900", "70100"),
			candle(LocalTime.of(9, 1), "70100", "70400", "70050", "70300")));
		collectorWith(fakeClient).collect();

		// 이 collector.collect() 호출은 STOCK 종목 전체(시드 16종 + 다른 테스트가 공유 DB에 이미 남겨 둔 종목 포함)를
		// 순회한다 — 이 테스트가 구성한 종목만 도는 것이 아니다. 따라서 전체 결과가 SUCCESS인지는 단정하지 않고(다른 테스트가
		// 6자리 숫자가 아닌 심볼의 종목을 남겨 뒀다면 그 종목만 구조 오류로 잡혀 PARTIAL_SUCCESS가 될 수 있다 — 그 자체가
		// 정상 동작이다), 이 시나리오의 대상 종목만은 실패 목록에 없다는 것으로 충분히 확인한다.
		List<MarketDataImport> imports = marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(TD_A);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0).getStatus()).isNotEqualTo(ImportStatus.FAILED);
		if (imports.get(0).getFailureReason() != null) {
			assertThat(imports.get(0).getFailureReason()).doesNotContain(instrument.getSymbol());
		}
		assertThat(stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrument.getId(), TD_A))
			.hasSize(2);

		// 08:40 KST — 재생세션을 READY로 확정.
		setClock(SD_A, LocalTime.of(8, 40));
		stockReplaySessionScheduler.resolveTodaySession();

		StockReplaySession session = stockReplaySessionRepository.findByServiceDate(SD_A).orElseThrow();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(TD_A);
		LocalDateTime firstResolvedAt = session.getResolvedAt();

		// 09:00:30 — 첫 분봉 구간(아직 마감 전) → 첫 분봉의 시가를 현재가로 노출. 가격 API와 SSE snapshot이 같은 값을 보여준다.
		setClock(SD_A, LocalTime.of(9, 0, 30));
		PriceQuoteDto openQuote = priceQueryService.getPrice(instrument.getId());
		assertThat(openQuote.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(openQuote.price()).isEqualByComparingTo("70000");
		assertThat(openQuote.sourceTradingDate()).isEqualTo(TD_A);
		assertThat(stockPriceProvider.getMarketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(snapshotPriceFor(stockPriceStreamService.buildSnapshot(), instrument.getSymbol()))
			.isEqualByComparingTo("70000");

		// 09:02:00 — 09:01 분봉까지 마감 완료 → 마지막으로 마감된 분봉(09:01)의 종가를 현재가로 노출.
		setClock(SD_A, LocalTime.of(9, 2, 0));
		PriceQuoteDto closeQuote = priceQueryService.getPrice(instrument.getId());
		assertThat(closeQuote.price()).isEqualByComparingTo("70300");
		assertThat(snapshotPriceFor(stockPriceStreamService.buildSnapshot(), instrument.getSymbol()))
			.isEqualByComparingTo("70300");

		// 화면 가격(가격 API·SSE)과 모의 주문 체결가가 같은 Provider(KisHistoricalReplayPriceProvider)에서 나오는지 확인 — 같은
		// 시각에 조회한 가격과 그 시각에 체결된 주문의 체결가가 동일해야 한다.
		User user = createUser("pipeline");
		pipelineUserId = user.getId();
		createAccount(user);
		OrderResponse orderResponse = orderService.createOrder(
			user.getId(), "idem-pipeline-1", buyRequest(instrument.getId()));
		assertThat(orderResponse.price()).isEqualByComparingTo(closeQuote.price());
		assertThat(orderResponse.price()).isEqualByComparingTo("70300");

		// 서버 재시작 시나리오 — 같은 서비스 날짜(SD_A)에 새 스케줄러 인스턴스로 재실행해도 원본 거래일과 확정 시각이 바뀌지 않는다.
		setClock(SD_A, LocalTime.of(10, 0));
		freshSchedulerInstanceAfterRestart().resolveTodaySession();

		StockReplaySession afterRestart = stockReplaySessionRepository.findByServiceDate(SD_A).orElseThrow();
		assertThat(afterRestart.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(afterRestart.getSourceTradingDate()).isEqualTo(TD_A);
		assertThat(afterRestart.getResolvedAt()).isEqualTo(firstResolvedAt);

		// DB에는 준비상태(PREPARING·READY·FAILED)만 저장되고 계산된 시장상태(OPEN·CLOSED)는 저장되지 않는다 — 지금(10:00,
		// 장중)도 DB 컬럼 값은 여전히 READY이지 "OPEN"이 아니며, OPEN은 조회 시점에 별도로 계산된 값일 뿐이다.
		assertThat(afterRestart.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(stockPriceProvider.getMarketStatus()).isEqualTo(StockMarketStatus.OPEN);
	}

	@Test
	void entireResponseErrorLeavesSessionFailedAndStockMarketClosed() {
		// 시나리오 A와 같은 실제 종목(index 0)을 재사용한다 — 거래일(TD_D)이 달라 StockCandle UNIQUE 제약과 충돌하지 않는다.
		Instrument instrument = realStockInstrument(0);

		// 08:10 KST — 대상 종목 전부에서 KisHistoricalCandleClient 호출이 예외를 던지는 상황을 흉내낸다(종목별 실패가
		// 모두 합쳐져 결과적으로 FAILED).
		setClock(SD_D, LocalTime.of(8, 10));
		collectorWith(new ThrowingKisHistoricalCandleClient()).collect();

		List<MarketDataImport> imports = marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(TD_D);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0).getStatus()).isEqualTo(ImportStatus.FAILED);
		assertThat(stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrument.getId(), TD_D))
			.isEmpty();

		// 08:40 KST — 검증 완료된 거래일을 하나도 찾지 못해 세션이 FAILED로 확정된다.
		setClock(SD_D, LocalTime.of(8, 40));
		stockReplaySessionScheduler.resolveTodaySession();

		StockReplaySession session = stockReplaySessionRepository.findByServiceDate(SD_D).orElseThrow();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		assertThat(session.getSourceTradingDate()).isNull();
		assertThat(session.getFailureReason()).isNotBlank();

		// 장중 시각이 되어도 주식 시장은 열리지 않고, 가격 조회는 PRICE_UNAVAILABLE로 거부된다.
		setClock(SD_D, LocalTime.of(9, 30));
		assertThat(stockPriceProvider.getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThatThrownBy(() -> priceQueryService.getPrice(instrument.getId()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	@Test
	void singleInstrumentStructuralErrorStillOpensMarketAndPricesOtherInstruments() {
		// 역시 실제 종목 두 개(index 0·1)를 재사용한다 — 거래일(TD_E)이 A·D와 달라 안전하다.
		Instrument goodInstrument = realStockInstrument(0);
		Instrument brokenInstrument = realStockInstrument(1);

		// 08:10 KST — 한 종목은 정상 분봉, 다른 종목은 동일 분봉시각 중복(구조 오류)으로 응답한다.
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

		// 08:40 KST — PARTIAL_SUCCESS도 검증 완료로 인정되어 세션은 READY로 확정된다(전체 시장은 정상 개장).
		setClock(SD_E, LocalTime.of(8, 40));
		stockReplaySessionScheduler.resolveTodaySession();

		StockReplaySession session = stockReplaySessionRepository.findByServiceDate(SD_E).orElseThrow();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(TD_E);

		// 장중 — 정상 종목은 가격이 조회되고, 손상 종목만 그날의 유효한 StockCandle이 없어 자연히 PRICE_UNAVAILABLE로 거부된다.
		setClock(SD_E, LocalTime.of(9, 5));
		assertThat(stockPriceProvider.getMarketStatus()).isEqualTo(StockMarketStatus.OPEN);

		PriceQuoteDto goodQuote = priceQueryService.getPrice(goodInstrument.getId());
		assertThat(goodQuote.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(goodQuote.price()).isEqualByComparingTo("50100");

		assertThatThrownBy(() -> priceQueryService.getPrice(brokenInstrument.getId()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	// PreparationStatus는 PREPARING·READY·FAILED 세 값만 존재한다 — OPEN·CLOSED는 이 타입 자체에 없으므로 구조적으로
	// stock_replay_sessions에 저장될 수 없다(spec.md 비즈니스 규칙: OPEN·CLOSED는 계산된 시장상태일 뿐 DB 상태가 아니다).
	@Test
	void preparationStatusEnumNeverRepresentsComputedOpenOrClosedMarketState() {
		assertThat(PreparationStatus.values())
			.containsExactlyInAnyOrder(PreparationStatus.PREPARING, PreparationStatus.READY, PreparationStatus.FAILED);
	}

}
