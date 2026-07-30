// 매수·매도 파이프라인(이슈 #13·#41)으로 생성한 실제 원장·시세 데이터를 GET /api/holdings로 검증하는 통합 테스트다.
package com.finplay.api.portfolio.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 이 테스트는 InstrumentRepositoryTest·StockReplaySessionRepositoryTest처럼 공유 MySQL 컨테이너(ADR-0003)에서
// 종목·재생세션 전체 개수·유니크 제약을 단정하는 슬라이스 테스트와 같은 테이블(instruments·stock_replay_sessions)에
// saveAndFlush로 실제 커밋을 남긴다. AccountSummaryIntegrationTest(이슈 #81, agent-mistakes.md 2026-07-30 항목)에서
// 이 조합이 `./gradlew build` 전체 실행 시 다른 클래스의 절대개수 단정을 깨뜨리는 것이 재현·확인됐으므로, 이 클래스도
// 동일 근거로 `@Transactional`을 붙여 각 테스트 종료 시 자동 롤백시킨다. MockMvc 호출은 테스트 메서드와 같은 스레드에서
// 동기 실행되어 같은 트랜잭션에 참여하므로 6개 값 검증 자체는 약화되지 않는다(다만 실제 커밋 경계 검증은 대상 밖).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, HoldingIntegrationTest.FixedClockTestConfig.class})
class HoldingIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다.
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderService orderService;

	@Autowired
	private Clock clock;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@BeforeEach
	void setUp() {
		((MutableClock)clock).set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@Test
	void fullySoldInstrumentIsExcludedAndRemainingHoldingHasAccurateSixValues() throws Exception {
		User user = createUser("hld-owner");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		Instrument sold = createStockInstrument("HOLDA");
		createCandle(sold, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(sold, SECOND_CANDLE_TIME, new BigDecimal("100000"));

		Instrument remaining = createStockInstrument("HOLDB");
		createCandle(remaining, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		createCandle(remaining, SECOND_CANDLE_TIME, new BigDecimal("90000"));

		// 매수 둘 다 10:00 시각 → 09:59 분봉이 체결가.
		orderService.createOrder(user.getId(), "holding-buy-sold", buyRequest(sold.getId(), "10"));
		orderService.createOrder(user.getId(), "holding-buy-remaining", buyRequest(remaining.getId(), "5"));

		// 10:01로 시각 이동 → 10:00 분봉이 체결가·최신 시세가 된다. sold 종목만 전량 매도.
		((MutableClock)clock).set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(user.getId(), "holding-sell-sold", sellRequest(sold.getId(), "10"));

		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].instrumentId").value(remaining.getId()))
			.andExpect(jsonPath("$[0].quantity").value(5))
			.andExpect(jsonPath("$[0].averagePrice").value(70000))
			.andExpect(jsonPath("$[0].currentPrice").value(90000))
			.andExpect(jsonPath("$[0].evaluationAmount").value(450000))
			.andExpect(jsonPath("$[0].unrealizedPnl").value(100000))
			.andExpect(jsonPath("$[0].returnRate").value(0.2857))
			.andExpect(jsonPath("$[0].priceStatus").value("AVAILABLE"));
	}

	// PR #97 리뷰 권장사항 3: 시세 무효(UNAVAILABLE) 종목의 "4개 필드 null + priceStatus=UNAVAILABLE" 정책이
	// 통합 레벨에서 한 번도 실행되지 않았다. 매수는 반드시 유효한 시세가 있어야 가능하므로(PriceQueryService),
	// 분봉을 아예 만들지 않은 종목은 정상 매수 흐름으로는 재현할 수 없다 — 대신 HoldingRepository로 holding을
	// 직접 심어 "매수 이후 해당 종목의 분봉이 전혀 없는" 상태를 재현한다(StockReplayService.getCurrentPrice는
	// 분봉이 없으면 세션이 READY여도 UNAVAILABLE을 반환한다).
	@Test
	void instrumentWithoutAnyCandleReturnsUnavailableWhileOtherHoldingStaysAvailable() throws Exception {
		User user = createUser("hld-badpx");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);

		Instrument available = createStockInstrument("HOLDAVAIL");
		createCandle(available, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		orderService.createOrder(user.getId(), "holding-badpx-buy", buyRequest(available.getId(), "10"));

		// 분봉을 전혀 만들지 않은 종목 — 정상 매수는 시세 유효성 검증(PriceQueryService.getPrice)을 통과해야
		// 하므로 이 종목은 주문 파이프라인을 거치지 않고 holding을 직접 저장해 "매수 이후 시세가 사라진" 상태를 재현한다.
		Instrument priceless = createStockInstrument("HOLDBADPX");
		Holding holding = Holding.create(account, priceless, BASE_NOW);
		holding.applyBuy(BigDecimal.valueOf(7), new BigDecimal("55000"), BASE_NOW);
		holdingRepository.saveAndFlush(holding);

		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			// ORDER BY symbol ASC: "HOLDAVAIL..." < "HOLDBADPX..." (다섯째 글자 'A' < 'B')
			.andExpect(jsonPath("$[0].instrumentId").value(available.getId()))
			.andExpect(jsonPath("$[0].currentPrice").value(60000))
			.andExpect(jsonPath("$[0].evaluationAmount").value(600000))
			.andExpect(jsonPath("$[0].unrealizedPnl").value(0))
			.andExpect(jsonPath("$[0].returnRate").value(0.0000))
			.andExpect(jsonPath("$[0].priceStatus").value("AVAILABLE"))
			.andExpect(jsonPath("$[1].instrumentId").value(priceless.getId()))
			.andExpect(jsonPath("$[1].quantity").value(7))
			.andExpect(jsonPath("$[1].averagePrice").value(55000))
			.andExpect(jsonPath("$[1].currentPrice").doesNotExist())
			.andExpect(jsonPath("$[1].evaluationAmount").doesNotExist())
			.andExpect(jsonPath("$[1].unrealizedPnl").doesNotExist())
			.andExpect(jsonPath("$[1].returnRate").doesNotExist())
			.andExpect(jsonPath("$[1].priceStatus").value("UNAVAILABLE"));
	}

	@Test
	void newAccountWithNoHoldingsReturnsEmptyArray() throws Exception {
		User user = createUser("hld-empty");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void otherUsersHoldingsDoNotLeakIntoOwnList() throws Exception {
		User owner = createUser("hld-lk-owner");
		createAccount(owner);
		String ownerAccessToken = issueAccessToken(owner);

		User other = createUser("hld-lk-other");
		createAccount(other);
		Instrument instrument = createStockInstrument("HOLDC");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));

		orderService.createOrder(other.getId(), "holding-other-buy", buyRequest(instrument.getId(), "10"));

		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerAccessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void missingOrInvalidMarketReturns400AndUnauthenticatedReturns401() throws Exception {
		User user = createUser("holding-invalid");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(get("/api/holdings")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get("/api/holdings")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get("/api/holdings").param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private OrderCreateRequest sellRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.SELL, "MARKET", new BigDecimal(quantity));
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, BASE_NOW));
	}

	private Instrument createStockInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 6);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, symbolPrefix + "종목", BigDecimal.ONE, 0L, true, BASE_NOW));
	}

	private void createCandle(Instrument instrument, LocalTime candleTime, BigDecimal price) {
		stockCandleRepository.saveAndFlush(StockCandle.create(
			instrument, TRADING_DATE, candleTime, price, price, price, price, 0L, "TEST", BASE_NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}

	// 전역 Clock 빈(ClockConfig, Asia/Seoul 실시각)을 이 테스트 컨텍스트에서만 고정 시각으로 교체한다.
	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return new MutableClock(BASE_NOW.atZone(KST).toInstant(), KST);
		}
	}

	// 매수 이후 매도·평가 시점을 다른 분봉으로 이동시키기 위해 시각을 전진시킬 수 있는 Clock 구현.
	private static final class MutableClock extends Clock {

		private final ZoneId zone;
		private volatile Instant instant;

		private MutableClock(Instant instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		void set(LocalDateTime localDateTime) {
			this.instant = localDateTime.atZone(zone).toInstant();
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
