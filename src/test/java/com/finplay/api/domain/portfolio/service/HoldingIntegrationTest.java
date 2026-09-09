package com.finplay.api.domain.portfolio.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class HoldingIntegrationTest {

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderService orderService;

	@Autowired
	private TestClock clock;

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
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@Test
	void fullySoldInstrumentIsExcludedAndRemainingHoldingHasAccurateSixValues() throws Exception {
		User user = createUser("hld-owner");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);

		Instrument sold = createStockInstrument("HOLDA");
		createCandle(sold, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(sold, SECOND_CANDLE_TIME, new BigDecimal("100000"));

		Instrument remaining = createStockInstrument("HOLDB");
		createCandle(remaining, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		createCandle(remaining, SECOND_CANDLE_TIME, new BigDecimal("90000"));

		orderService.createOrder(user.getId(), "holding-buy-sold", buyRequest(sold.getId(), "10"));
		orderService.createOrder(user.getId(), "holding-buy-remaining", buyRequest(remaining.getId(), "5"));

		clock.set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(user.getId(), "holding-sell-sold", sellRequest(sold.getId(), "10"));

		Long remainingHoldingId = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), remaining.getId())
			.orElseThrow()
			.getId();

		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].holdingId").value(remainingHoldingId))
			.andExpect(jsonPath("$[0].instrumentId").value(remaining.getId()))
			.andExpect(jsonPath("$[0].quantity").value(5))
			.andExpect(jsonPath("$[0].averagePrice").value(70000))
			.andExpect(jsonPath("$[0].currentPrice").value(90000))
			.andExpect(jsonPath("$[0].evaluationAmount").value(450000))
			.andExpect(jsonPath("$[0].unrealizedPnl").value(100000))
			.andExpect(jsonPath("$[0].returnRate").value(0.2857))
			.andExpect(jsonPath("$[0].priceStatus").value("AVAILABLE"));
	}

	@Test
	void instrumentWithoutAnyCandleReturnsUnavailableWhileOtherHoldingStaysAvailable() throws Exception {
		User user = createUser("hld-badpx");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);

		Instrument available = createStockInstrument("HOLDAVAIL");
		createCandle(available, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		orderService.createOrder(user.getId(), "holding-badpx-buy", buyRequest(available.getId(), "10"));

		Instrument priceless = createStockInstrument("HOLDBADPX");
		Holding holding = Holding.create(account, priceless, BASE_NOW);
		holding.applyBuy(BigDecimal.valueOf(7), new BigDecimal("55000"), BASE_NOW);
		holdingRepository.saveAndFlush(holding);

		Long availableHoldingId = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), available.getId())
			.orElseThrow()
			.getId();

		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].holdingId").value(availableHoldingId))
			.andExpect(jsonPath("$[0].instrumentId").value(available.getId()))
			.andExpect(jsonPath("$[0].currentPrice").value(60000))
			.andExpect(jsonPath("$[0].evaluationAmount").value(600000))
			.andExpect(jsonPath("$[0].unrealizedPnl").value(0))
			.andExpect(jsonPath("$[0].returnRate").value(0.0000))
			.andExpect(jsonPath("$[0].priceStatus").value("AVAILABLE"))
			.andExpect(jsonPath("$[1].holdingId").value(holding.getId()))
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
			Account.create(user, Market.STOCK, BASE_NOW));
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

}
