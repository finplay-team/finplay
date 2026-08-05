// 지정가 매수 생성 → 빗썸 가격 갱신 이벤트 발행 → 체결까지 실제 Spring 컨텍스트(Testcontainers MySQL)로
// 엔드투엔드 배선을 검증하는 통합 테스트다. LimitOrderFillServiceTest 등 단위 테스트는 서비스 메서드를 직접
// 호출해 @EventListener 배선(PriceStore → CryptoPriceUpdatedEvent → LimitOrderTriggerListener) 자체는
// 검증하지 못하므로, "체결"이 처음 등장하는 이 항목에서 최소 1개의 실배선 확인 테스트를 둔다(tasks.md 항목4,
// 항목6의 동시성 통합테스트와는 검증 대상이 달라 중복이 아니다 — 항목6은 fillIfPending을 직접 호출한다).
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
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.Clock;
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
	// CryptoPriceUpdatedEvent를 publish) → 같은 스레드에서 동기 실행되는 LimitOrderTriggerListener가
	// LimitOrderFillService.fillIfPending을 호출해 체결까지 끝낸다. saveTick 호출이 반환한 시점에는 이미
	// 체결 트랜잭션이 커밋돼 있어야 한다(AFTER_COMMIT이 아닌 일반 리스너, spec.md 확정 설계 결정 3번).
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

		var filledOrder = orderRepository.findById(orderId).orElseThrow();
		assertThat(filledOrder.getStatus()).isEqualTo(OrderStatus.FILLED);

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
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, LocalDateTime.now(clock)));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
