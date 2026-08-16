// 일반 경로로 생성한 OCO가 실제 가격 틱(PriceStore.saveTick → CryptoPriceUpdatedEvent →
// ExitPlanTriggerListener → ExitPlanFillService)으로 익절 체결되는 핵심 시나리오를 검증하는 통합 테스트다
// (021 tasks.md 항목5 "가격 트리거·GTC 체결"). PR #368 리뷰 차단 1(OSIV-off LazyInitializationException)의
// 재발을 이 기능에서도 잡기 위해 클래스/메서드에 @Transactional을 걸지 않는다 —
// ExitPlanGeneralPathOsivBoundaryIntegrationTest와 동일한 근거다: 트리거 경로 전체(HTTP 생성 요청, 리스너의
// 이벤트 수신, ExitPlanFillService.fillIfPending의 자체 @Transactional)가 실제 운영처럼 각자 자기
// 트랜잭션·세션 안에서만 lazy 필드에 접근해야 한다.
package com.finplay.api.order;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.finplay.api.order.domain.ExitPlanCondition;
import com.finplay.api.order.domain.ExitPlanConditionStatus;
import com.finplay.api.order.domain.ExitPlanConditionType;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.repository.ExitPlanConditionRepository;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
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

	// price:crypto:{symbol} 키는 실행 시점에야 심볼을 알 수 있어 필드로 기억해뒀다 @AfterEach에서 지운다
	// (ExitPlanGeneralPathOsivBoundaryIntegrationTest와 동일 관례 — 지우지 않으면 이후 테스트의 saveTick이
	// "과거 틱은 무시" 가드에 걸린다).
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
		// FK 역순으로 지운다: exit_plan_conditions/idempotency_keys → exit_plans(→orders 참조) →
		// trade_allocations → holding_lots(→trades 참조) → trades(→orders 참조) → orders → holdings → accounts → users.
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

	// 시나리오: 일반 경로로 OCO 생성(HTTP) → 가격 틱을 익절가 이상으로 저장(PriceStore.saveTick이 실제로
	// CryptoPriceUpdatedEvent를 publish) → ExitPlanTriggerListener가 동기 실행돼
	// ExitPlanFillService.fillIfPending을 호출해 체결까지 끝낸다. saveTick 호출이 반환한 시점에는 이미 체결
	// 트랜잭션이 커밋돼 있어야 한다(일반 리스너, AFTER_COMMIT 아님 — LimitOrderFillIntegrationTest와 동일
	// 설계 결정). 이 테스트 전체에 테스트 트랜잭션이 없으므로, 리스너·체결 서비스가 lazy 필드를 트랜잭션 밖에서
	// 건드리는 지점이 남아있다면 LazyInitializationException으로 여기서 드러난다.
	@Test
	@DisplayName("익절가 이상 가격 틱이 오면 plan이 FILLED_TAKE_PROFIT으로 전이하고 holding 예약이 정확히 소비되며 반대(STOP_LOSS) 조건이 취소된다")
	void priceTickAtOrAboveTakeProfitFillsPlanConsumesReservationAndCancelsOppositeCondition() throws Exception {
		User user = userRepository.saveAndFlush(User.create(EMAIL, "password-hash", "trigger-fill", NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW));
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		Instrument instrument = cryptos.get(0);
		cryptoPriceKeyToCleanUp = "price:crypto:" + instrument.getSymbol();
		// MARKET 매수 체결(OrderExecutionService)은 표시 경로(OCO 생성)와 달리 stale 가격을 fail-closed로
		// 거부한다(PriceQueryService.getCryptoExecutionPriceQuote, PRICE-STALE-003) — 그래서 고정된 과거
		// NOW가 아니라 실제 Clock 기준 현재시각으로 틱을 저장해야 한다(LimitOrderFillIntegrationTest와 동일 관례).
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), LocalDateTime.now(clock));

		// holding을 실제 시장가 매수(MARKET BUY) 주문으로 만든다 — holding.applyBuy()를 직접 호출하면
		// PortfolioBuyService가 만드는 HoldingLot이 생기지 않아, 이후 체결이 lot 배분(PortfolioSellService)
		// 단계에서 "보유 lot 잔여수량 합계가 holding 보유수량과 일치하지 않습니다" IllegalStateException으로
		// 실패한다(실제로 재현). 실제 매수 흐름을 그대로 거쳐야 체결까지 온전히 검증할 수 있다.
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

		// 익절가 이상으로 가격 틱을 저장한다 — 이 호출이 반환하면 체결 트랜잭션까지 이미 끝나 있어야 한다.
		priceStore.saveTick(instrument.getSymbol(), takeProfitPrice, LocalDateTime.now(clock));

		ExitPlan filledPlan = exitPlanRepository.findById(exitPlanId).orElseThrow();
		assertThat(filledPlan.getStatus()).isEqualTo(ExitPlanStatus.FILLED_TAKE_PROFIT);
		assertThat(filledPlan.getTriggeredOrder()).isNotNull();
		assertThat(filledPlan.getClosedAt()).isNotNull();

		Holding afterFill = holdingRepository.findById(holding.getId()).orElseThrow();
		// 체결로 예약이 정확히 소비된다 — 예약도, 총 보유수량도 매도분만큼 줄어든다.
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
}
