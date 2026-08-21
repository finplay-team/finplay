// 지정가 매수 생성 → 빗썸 가격 갱신 이벤트 발행 → 체결까지 실제 Spring 컨텍스트(Testcontainers MySQL)로
// 엔드투엔드 배선을 검증하는 통합 테스트다. LimitOrderFillServiceTest 등 단위 테스트는 서비스 메서드를 직접
// 호출해 @EventListener 배선(PriceStore → CryptoPriceUpdatedEvent → LimitOrderTriggerListener) 자체는
// 검증하지 못하므로, "체결"이 처음 등장하는 이 항목에서 최소 1개의 실배선 확인 테스트를 둔다(tasks.md 항목4,
// 항목6의 동시성 통합테스트와는 검증 대상이 달라 중복이 아니다 — 항목6은 fillIfPending을 직접 호출한다).
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
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LimitOrderFillIntegrationTest {

	private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private Clock clock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete("feed:crypto:status");
	}

	// 시나리오: BUY 지정가 생성(PENDING, 현금 예약) → 지정가 이하로 가격 틱 저장(PriceStore.saveTick이 실제로
	// CryptoPriceUpdatedEvent를 publish) → LimitOrderTriggerListener가 후보를 조회해 실행기(ADR-0024)에
	// 위임하고 즉시 반환한다. 실제 체결(LimitOrderFillService.fillBatch)은 파티션 전용 스레드에서 비동기로
	// 끝나므로, saveTick 호출이 반환한 시점에 곧바로 상태를 확인하면 안 되고 체결 완료(FILLED)를 폴링해야 한다.
	@Test
	void limitBuyOrderFillsEndToEndWhenPriceTickReachesLimitPrice() throws Exception {
		User user = createUser("lmt-fill-e2e");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();

		BigDecimal quantity = new BigDecimal("0.01");
		BigDecimal limitPrice = new BigDecimal("50000000");

		String body = performCreateLimitOrder(accessToken, instrument.getId(), "BUY", quantity, limitPrice)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn().getResponse().getContentAsString();
		Long orderId = objectMapper.readTree(body).get("orderId").asLong();

		Account reservedAccount = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(reservedAccount.getReservedCash()).isGreaterThan(0L);
		long cashBeforeFill = reservedAccount.getCashBalance();
		long reservedCashBeforeFill = reservedAccount.getReservedCash();

		// 지정가 이하로 가격 틱을 저장한다 — BUY 체결 조건(현재가 ≤ 지정가)을 충족시켜 리스너를 실제로 촉발한다.
		priceStore.saveTick(instrument.getSymbol(), limitPrice, LocalDateTime.now(clock));

		// 체결은 파티션 전용 스레드에서 비동기로 끝난다(ADR-0024) — 완료될 때까지 폴링한다.
		awaitUntil(
			() -> orderRepository.findById(orderId).orElseThrow().getStatus() == OrderStatus.FILLED,
			Duration.ofSeconds(5), "주문이 제한 시간 안에 체결되지 않았다");

		Account accountAfterFill = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(accountAfterFill.getReservedCash()).isZero();
		assertThat(accountAfterFill.getCashBalance()).isLessThan(cashBeforeFill);
		assertThat(cashBeforeFill - accountAfterFill.getCashBalance())
			.isEqualTo(reservedCashBeforeFill);

		List<Holding> holdings = holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId());
		assertThat(holdings)
			.filteredOn(h -> h.getInstrument().getId().equals(instrument.getId()))
			.singleElement()
			.satisfies(h -> assertThat(h.getQuantity()).isEqualByComparingTo(quantity));
	}

	private ResultActions performCreateLimitOrder(
		String accessToken, Long instrumentId, String side, BigDecimal quantity, BigDecimal limitPrice)
		throws Exception {
		String requestJson = """
			{"market":"CRYPTO","instrumentId":%d,"side":"%s","quantity":%s,"limitPrice":%s}
			""".formatted(instrumentId, side, quantity.toPlainString(), limitPrice.toPlainString());
		return mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson));
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
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), LocalDateTime.now(clock)));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, LocalDateTime.now(clock)));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	// LimitOrderAsyncFillConcurrencyIntegrationTest의 awaitUntil 관례를 그대로 따른다.
	private static void awaitUntil(
		java.util.function.BooleanSupplier condition, Duration timeout, String failureMessage) {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(failureMessage, e);
			}
		}
		throw new AssertionError(failureMessage);
	}
}
