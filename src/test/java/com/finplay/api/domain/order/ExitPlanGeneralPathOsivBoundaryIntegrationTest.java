// 테스트 트랜잭션 없이 실제 HTTP 요청으로 OCO 생성을 돌려 open-in-view=false 경계를 지키는 회귀 테스트다.
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
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
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

/**
 * PR #368 리뷰 차단 1: {@code ExitPlanGeneralPathIntegrationTest}는 클래스에 {@code @Transactional}이 있어
 * 테스트 트랜잭션이 요청 전체를 감싼다 — {@code HoldingService.findHoldingForOwner}가 자신의 {@code
 * @Transactional(readOnly = true)}를 잃고 인라인돼도 세션이 테스트 트랜잭션 덕에 여전히 열려 있어 {@code
 * holding.getInstrument()} 접근이 통과해버린다. 운영은 {@code spring.jpa.open-in-view: false}라 실제로는 각
 * HTTP 요청이 자기 트랜잭션 안에서만 세션을 연다.
 *
 * <p>**이 클래스에 {@code @Transactional}이 없는 것이 존재 이유다** — {@code
 * PostSellFeedbackBoundaryIntegrationTest}와 같은 선례를 따른다. 커밋한 행은 {@code @AfterEach}가 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExitPlanGeneralPathOsivBoundaryIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0, 0);
	private static final String EMAIL = "exit-plan-osiv-boundary@finplay.com";

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
	private PriceStore priceStore;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// saveTick으로 저장한 "price:crypto:{symbol}" 키를 @AfterEach에서 지우려면 심볼을 기억해야 한다(테스트가
	// cryptos.get(0)의 실제 심볼을 실행 시점에야 알 수 있다) — AccountSummaryIntegrationTest 등 기존 관례.
	private String cryptoPriceKeyToCleanUp;

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	// 커밋한 행을 FK 역순으로 지운다 — instrument는 이 테스트가 만들지 않은 기존 종목을 재사용하므로 지우지 않는다.
	// price:crypto:{symbol} 키도 반드시 지운다 — 지우지 않으면 이 테스트가 심어둔 receivedAt이 다른 테스트가
	// 같은(공유) 첫 코인 종목에 실제 Clock.now()로 찍는 이후 tick보다 미래로 남아, PriceStore.saveTick의
	// "과거 틱은 무시" 가드에 걸려 그 테스트의 CryptoPriceUpdatedEvent가 발행되지 않는다(실제로 재현: 이 키를
	// 지우지 않은 채로 두면 LimitOrderFillIntegrationTest가 PENDING에 멈춰 FILLED 단정에서 실패한다).
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
		jdbcTemplate.update(
			"delete from holdings where account_id in "
				+ "(select id from accounts where user_id in (select id from users where email = ?))",
			EMAIL);
		jdbcTemplate.update("delete from accounts where user_id in (select id from users where email = ?)", EMAIL);
		jdbcTemplate.update("delete from users where email = ?", EMAIL);
	}

	// HoldingService.findHoldingForOwner의 @Transactional이 사라지거나 instrument를 즉시 로딩하지 않으면,
	// 테스트 트랜잭션이 없는 이 흐름에서 holding.getInstrument().getMarket() 접근이
	// LazyInitializationException으로 터져 500 INTERNAL_ERROR가 된다(PR #368 리뷰 차단 1 재현).
	@Test
	@DisplayName("테스트 트랜잭션 없이도 holding 조회→시장 검증→생성이 통과한다 — instrument LAZY 접근이 세션 밖에서 안전하다")
	void createExitPlanSucceedsWithoutATestTransactionWrappingTheLazyInstrumentAccess() throws Exception {
		User user = userRepository.saveAndFlush(User.create(EMAIL, "password-hash", "osiv-boundary", NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		Instrument instrument = cryptos.get(0);
		cryptoPriceKeyToCleanUp = "price:crypto:" + instrument.getSymbol();
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
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.holdingId").value(holding.getId()))
			.andExpect(jsonPath("$.status").value("PENDING"));
	}
}
