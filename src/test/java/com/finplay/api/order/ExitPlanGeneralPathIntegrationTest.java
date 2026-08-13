// POST/DELETE /api/exit-plans 일반 경로(intentionId 생략) 생성→취소 전체 흐름을 컨트롤러 경유로 검증하는 통합
// 테스트다(021 plan.md "테스트 계획" — 일반 경로 전체 흐름, 예약 반환 검증). ADR-0003 "핵심 시나리오 통합 테스트 1개".
package com.finplay.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
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

	// 시나리오: 코인 holding 보유자가 PRICE 방식 OCO를 생성하면 holding.reservedQuantity가 요청 수량만큼 늘고,
	// 그 예약을 취소하면 정확히 그만큼 원복되며 plan 상태는 CANCELLED로 종결된다(021 plan.md "정확히 한 번 규칙").
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

	// 이미 대기 중인 예약이 있는 holding에 두 번째 생성을 시도하면 흔적 없이 409로 거부된다(021 RISK-OCO-004).
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
		assertThat(plansOnHolding).hasSize(1); // 두 번째 시도는 흔적을 남기지 않았다.

		Holding afterSecondAttempt = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterSecondAttempt.getReservedQuantity()).isEqualByComparingTo("1.00000000"); // 최초 예약만 유지된다.
	}

	// 주식 holding으로 일반 경로 OCO 생성을 시도하면 400으로 거부되고 흔적을 남기지 않는다(021 RISK-OCO-006).
	@Test
	void createRejectsStockHoldingWithoutReservingOrPersistingAnything() throws Exception {
		User user = createUser("exit-plan-stock");
		Account stockAccount = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, NOW));
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

	private Instrument firstCryptoInstrument() {
		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		return cryptos.get(0);
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
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
