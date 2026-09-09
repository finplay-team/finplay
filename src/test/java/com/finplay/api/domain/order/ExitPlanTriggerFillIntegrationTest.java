package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanCondition;
import com.finplay.api.domain.order.entity.ExitPlanConditionStatus;
import com.finplay.api.domain.order.entity.ExitPlanConditionType;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.repository.ExitPlanConditionRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExitPlanTriggerFillIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0, 0);
	private static final String EMAIL = "exit-plan-trigger-fill@finplay.com";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private ExitPlanRepository exitPlanRepository;

	@Autowired
	private ExitPlanConditionRepository exitPlanConditionRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	private String cryptoPriceKeyToCleanUp;

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void tearDown() {
		if (cryptoPriceKeyToCleanUp != null) {
			redisTemplate.delete(cryptoPriceKeyToCleanUp);
		}
		redisTemplate.delete("feed:crypto:status");
		jdbcTemplate.update("delete from exit_plan_conditions where exit_plan_id in "
			+ "(select id from exit_plans where user_id in (select id from users where email = ?))", EMAIL);
		jdbcTemplate.update("delete from exit_plan_idempotency_keys where user_id in "
			+ "(select id from users where email = ?)", EMAIL);
		jdbcTemplate.update(
			"delete from exit_plans where user_id in (select id from users where email = ?)", EMAIL);
		jdbcTemplate.update("delete from trade_allocations where holding_lot_id in "
			+ "(select id from holding_lots where holding_id in "
			+ "(select id from holdings where account_id in "
			+ "(select id from accounts where user_id in (select id from users where email = ?))))", EMAIL);
		jdbcTemplate.update("delete from holding_lots where holding_id in "
			+ "(select id from holdings where account_id in "
			+ "(select id from accounts where user_id in (select id from users where email = ?)))", EMAIL);
		jdbcTemplate.update("delete from trades where order_id in "
			+ "(select id from orders where user_id in (select id from users where email = ?))", EMAIL);
		jdbcTemplate.update("delete from orders where user_id in (select id from users where email = ?)", EMAIL);
		jdbcTemplate.update(
			"delete from holdings where account_id in "
				+ "(select id from accounts where user_id in (select id from users where email = ?))",
			EMAIL);
		jdbcTemplate.update("delete from accounts where user_id in (select id from users where email = ?)", EMAIL);
		jdbcTemplate.update("delete from users where email = ?", EMAIL);
	}

	@Test
	@DisplayName("익절가 이상 가격 틱이 오면 plan이 FILLED_TAKE_PROFIT으로 전이하고 holding 예약이 정확히 소비되며 반대(STOP_LOSS) 조건이 취소된다")
	void priceTickAtOrAboveTakeProfitFillsPlanConsumesReservationAndCancelsOppositeCondition() throws Exception {
		User user = userRepository.saveAndFlush(User.create(EMAIL, "password-hash", "trigger-fill", NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		Instrument instrument = cryptos.get(0);
		cryptoPriceKeyToCleanUp = "price:crypto:" + instrument.getSymbol();
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), LocalDateTime.now(clock));

		String buyBody = """
			{"market":"CRYPTO","instrumentId":%d,"side":"BUY","orderType":"MARKET","quantity":"10.00000000"}
			""".formatted(instrument.getId());
		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(buyBody))
			.andExpect(status().isCreated());

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		BigDecimal reservedQuantity = new BigDecimal("1.00000000");
		BigDecimal takeProfitPrice = new BigDecimal("110000.00000000");
		String createBody = """
			{"holdingId":%d,"quantity":"%s","exitPriceType":"PRICE",
			"stopLoss":"95000.00000000","takeProfit":"%s"}
			""".formatted(holding.getId(), reservedQuantity, takeProfitPrice);

		String responseBody = mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn().getResponse().getContentAsString();
		Long exitPlanId = ((Number)com.jayway.jsonpath.JsonPath.read(responseBody, "$.id")).longValue();

		Holding afterCreate = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterCreate.getReservedQuantity()).isEqualByComparingTo(reservedQuantity);

		priceStore.saveTick(instrument.getSymbol(), takeProfitPrice, LocalDateTime.now(clock));

		ExitPlan filledPlan = exitPlanRepository.findById(exitPlanId).orElseThrow();
		assertThat(filledPlan.getStatus()).isEqualTo(ExitPlanStatus.FILLED_TAKE_PROFIT);
		assertThat(filledPlan.getTriggeredOrder()).isNotNull();
		assertThat(filledPlan.getClosedAt()).isNotNull();

		Holding afterFill = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterFill.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(afterFill.getQuantity()).isEqualByComparingTo("9.00000000");

		List<ExitPlanCondition> conditions = exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(exitPlanId);
		assertThat(conditions).hasSize(2);
		assertThat(conditions)
			.filteredOn(c -> c.getConditionType() == ExitPlanConditionType.TAKE_PROFIT)
			.singleElement()
			.satisfies(c -> assertThat(c.getStatus()).isEqualTo(ExitPlanConditionStatus.TRIGGERED));
		assertThat(conditions)
			.filteredOn(c -> c.getConditionType() == ExitPlanConditionType.STOP_LOSS)
			.singleElement()
			.satisfies(c -> assertThat(c.getStatus()).isEqualTo(ExitPlanConditionStatus.CANCELLED_BY_OCO));
	}

	@Test
	@DisplayName("손절가 이하 가격 틱이 오면 plan이 FILLED_STOP_LOSS로 전이하고 holding 예약이 정확히 소비되며 반대(TAKE_PROFIT) 조건이 취소된다")
	void priceTickAtOrBelowStopLossFillsPlanConsumesReservationAndCancelsOppositeCondition() throws Exception {
		User user = userRepository.saveAndFlush(User.create(EMAIL, "password-hash", "trigger-fill", NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		Instrument instrument = cryptos.get(0);
		cryptoPriceKeyToCleanUp = "price:crypto:" + instrument.getSymbol();
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), LocalDateTime.now(clock));

		String buyBody = """
			{"market":"CRYPTO","instrumentId":%d,"side":"BUY","orderType":"MARKET","quantity":"10.00000000"}
			""".formatted(instrument.getId());
		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(buyBody))
			.andExpect(status().isCreated());

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		BigDecimal reservedQuantity = new BigDecimal("1.00000000");
		BigDecimal stopLossPrice = new BigDecimal("95000.00000000");
		String createBody = """
			{"holdingId":%d,"quantity":"%s","exitPriceType":"PRICE",
			"stopLoss":"%s","takeProfit":"110000.00000000"}
			""".formatted(holding.getId(), reservedQuantity, stopLossPrice);

		String responseBody = mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn().getResponse().getContentAsString();
		Long exitPlanId = ((Number)com.jayway.jsonpath.JsonPath.read(responseBody, "$.id")).longValue();

		Holding afterCreate = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterCreate.getReservedQuantity()).isEqualByComparingTo(reservedQuantity);

		priceStore.saveTick(instrument.getSymbol(), stopLossPrice, LocalDateTime.now(clock));

		ExitPlan filledPlan = exitPlanRepository.findById(exitPlanId).orElseThrow();
		assertThat(filledPlan.getStatus()).isEqualTo(ExitPlanStatus.FILLED_STOP_LOSS);
		assertThat(filledPlan.getTriggeredOrder()).isNotNull();
		assertThat(filledPlan.getClosedAt()).isNotNull();

		Holding afterFill = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterFill.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(afterFill.getQuantity()).isEqualByComparingTo("9.00000000");

		List<ExitPlanCondition> conditions = exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(exitPlanId);
		assertThat(conditions).hasSize(2);
		assertThat(conditions)
			.filteredOn(c -> c.getConditionType() == ExitPlanConditionType.STOP_LOSS)
			.singleElement()
			.satisfies(c -> assertThat(c.getStatus()).isEqualTo(ExitPlanConditionStatus.TRIGGERED));
		assertThat(conditions)
			.filteredOn(c -> c.getConditionType() == ExitPlanConditionType.TAKE_PROFIT)
			.singleElement()
			.satisfies(c -> assertThat(c.getStatus()).isEqualTo(ExitPlanConditionStatus.CANCELLED_BY_OCO));
	}
}
