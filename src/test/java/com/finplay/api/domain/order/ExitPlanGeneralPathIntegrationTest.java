package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class ExitPlanGeneralPathIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 10, 0, 0);

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
	private PriceStore priceStore;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete("feed:crypto:status");
	}

	@Test
	void createThenCancelReservesAndFullyReleasesReservedQuantity() throws Exception {
		User user = createUser("exit-plan-general");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), NOW);

		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		holdingRepository.saveAndFlush(holding);

		String createBody = """
			{"holdingId":%d,"quantity":"1.00000000","exitPriceType":"PRICE",
			"stopLoss":"95000.00000000","takeProfit":"110000.00000000"}
			""".formatted(holding.getId());

		String responseBody = mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.holdingId").value(holding.getId()))
			.andExpect(jsonPath("$.intentionId").doesNotExist())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn().getResponse().getContentAsString();

		Number rawExitPlanId = com.jayway.jsonpath.JsonPath.read(responseBody, "$.id");
		Long exitPlanId = rawExitPlanId.longValue();

		Holding afterCreate = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterCreate.getReservedQuantity()).isEqualByComparingTo("1.00000000");
		assertThat(afterCreate.getAvailableQuantity()).isEqualByComparingTo("9.00000000");

		mockMvc.perform(delete("/api/exit-plans/{exitPlanId}", exitPlanId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		Holding afterCancel = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterCancel.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(afterCancel.getAvailableQuantity()).isEqualByComparingTo("10.00000000");

		ExitPlan cancelledPlan = exitPlanRepository.findById(exitPlanId).orElseThrow();
		assertThat(cancelledPlan.getStatus()).isEqualTo(ExitPlanStatus.CANCELLED);
		assertThat(cancelledPlan.getClosedAt()).isNotNull();
	}

	@Test
	void createRejectsSecondPendingPlanOnSameHoldingWithoutLeavingTraces() throws Exception {
		User user = createUser("exit-plan-dup");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), NOW);

		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		holdingRepository.saveAndFlush(holding);

		String createBody = """
			{"holdingId":%d,"quantity":"1.00000000","exitPriceType":"PRICE",
			"stopLoss":"95000.00000000","takeProfit":"110000.00000000"}
			""".formatted(holding.getId());

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EXIT_PLAN_ALREADY_EXISTS"));

		List<ExitPlan> plansOnHolding = exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(
			user.getId(), ExitPlanStatus.PENDING);
		assertThat(plansOnHolding).hasSize(1);

		Holding afterSecondAttempt = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterSecondAttempt.getReservedQuantity()).isEqualByComparingTo("1.00000000");
	}

	@Test
	void createRejectsStockHoldingWithoutReservingOrPersistingAnything() throws Exception {
		User user = createUser("exit-plan-stock");
		Account stockAccount = accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, NOW));
		String accessToken = issueAccessToken(user);
		Instrument stockInstrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "STK" + UUID.randomUUID().toString().substring(0, 6), "테스트종목",
				BigDecimal.ONE, 0L, true, NOW));

		Holding stockHolding = holdingRepository.saveAndFlush(Holding.create(stockAccount, stockInstrument, NOW));
		stockHolding.applyBuy(new BigDecimal("10"), new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(stockHolding);

		String createBody = """
			{"holdingId":%d,"quantity":"1","exitPriceType":"PRICE","stopLoss":"45000","takeProfit":"55000"}
			""".formatted(stockHolding.getId());

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		Holding afterAttempt = holdingRepository.findById(stockHolding.getId()).orElseThrow();
		assertThat(afterAttempt.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(user.getId(), ExitPlanStatus.PENDING))
			.isEmpty();
	}

	@Test
	void createRejectsTutorialSampleInstrumentHoldingWithoutReservingOrPersistingAnything() throws Exception {
		User user = createUser("exit-plan-tutorial-sample");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument tutorialInstrument = createTutorialSampleCryptoInstrument();
		priceStore.saveTick(tutorialInstrument.getSymbol(), new BigDecimal("100500.00000000"), NOW);

		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, tutorialInstrument, NOW));
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		holdingRepository.saveAndFlush(holding);

		String createBody = """
			{"holdingId":%d,"quantity":"1.00000000","exitPriceType":"PRICE",
			"stopLoss":"95000.00000000","takeProfit":"110000.00000000"}
			""".formatted(holding.getId());

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED"));

		Holding afterAttempt = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterAttempt.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(user.getId(), ExitPlanStatus.PENDING))
			.isEmpty();
	}

	@Test
	void createWithPercentThenListThenCancelReflectsRateAndReleasesReservation() throws Exception {
		User user = createUser("exit-plan-percent");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), NOW);

		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		holdingRepository.saveAndFlush(holding);

		String createBody = """
			{"holdingId":%d,"quantity":"1.00000000","exitPriceType":"PERCENT",
			"stopLossRate":"5.0000","takeProfitRate":"10.0000"}
			""".formatted(holding.getId());

		String responseBody = mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.exitPriceType").value("PERCENT"))
			.andExpect(jsonPath("$.stopLossPrice").value(95000.00000000))
			.andExpect(jsonPath("$.takeProfitPrice").value(110000.00000000))
			.andReturn().getResponse().getContentAsString();
		Long exitPlanId = ((Number)com.jayway.jsonpath.JsonPath.read(responseBody, "$.id")).longValue();

		mockMvc.perform(get("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].id").value(exitPlanId))
			.andExpect(jsonPath("$.content[0].holdingId").value(holding.getId()))
			.andExpect(jsonPath("$.content[0].exitPriceType").value("PERCENT"))
			.andExpect(jsonPath("$.content[0].status").value("PENDING"));

		mockMvc.perform(delete("/api/exit-plans/{exitPlanId}", exitPlanId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		Holding afterCancel = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterCancel.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(exitPlanRepository.findById(exitPlanId).orElseThrow().getStatus())
			.isEqualTo(ExitPlanStatus.CANCELLED);
	}

	@Test
	void createSucceedsAgainOnSameHoldingAfterPriorPlanIsCancelled() throws Exception {
		User user = createUser("exit-plan-regen");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), NOW);

		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		holdingRepository.saveAndFlush(holding);

		String createBody = """
			{"holdingId":%d,"quantity":"1.00000000","exitPriceType":"PRICE",
			"stopLoss":"95000.00000000","takeProfit":"110000.00000000"}
			""".formatted(holding.getId());

		String firstResponse = mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		Long firstPlanId = ((Number)com.jayway.jsonpath.JsonPath.read(firstResponse, "$.id")).longValue();

		mockMvc.perform(delete("/api/exit-plans/{exitPlanId}", firstPlanId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		String secondResponse = mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn().getResponse().getContentAsString();
		Long secondPlanId = ((Number)com.jayway.jsonpath.JsonPath.read(secondResponse, "$.id")).longValue();

		assertThat(secondPlanId).isNotEqualTo(firstPlanId);
		Holding afterSecondCreate = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterSecondCreate.getReservedQuantity()).isEqualByComparingTo("1.00000000");
	}

	@Test
	void replayingSameIdempotencyKeyReturnsIdenticalResponseWithoutCreatingSecondPlan() throws Exception {
		User user = createUser("exit-plan-idem");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), NOW);

		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		holdingRepository.saveAndFlush(holding);

		String idempotencyKey = UUID.randomUUID().toString();
		String createBody = """
			{"holdingId":%d,"quantity":"1.00000000","exitPriceType":"PRICE",
			"stopLoss":"95000.00000000","takeProfit":"110000.00000000"}
			""".formatted(holding.getId());

		String firstResponse = mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", idempotencyKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		Long firstPlanId = ((Number)com.jayway.jsonpath.JsonPath.read(firstResponse, "$.id")).longValue();

		String secondResponse = mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", idempotencyKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		Long secondPlanId = ((Number)com.jayway.jsonpath.JsonPath.read(secondResponse, "$.id")).longValue();

		assertThat(secondPlanId).isEqualTo(firstPlanId);
		assertThat(secondResponse).isEqualTo(firstResponse);
		assertThat(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(user.getId(), ExitPlanStatus.PENDING))
			.hasSize(1);

		Holding afterReplay = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterReplay.getReservedQuantity()).isEqualByComparingTo("1.00000000");
	}

	private Instrument firstCryptoInstrument() {
		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		return cryptos.get(0);
	}

	private Instrument createTutorialSampleCryptoInstrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), "샌드박스코인",
			BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrumentRepository.saveAndFlush(instrument);
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
