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
		jdbcTemplate.update(
			"delete from holdings where account_id in "
				+ "(select id from accounts where user_id in (select id from users where email = ?))",
			EMAIL);
		jdbcTemplate.update("delete from accounts where user_id in (select id from users where email = ?)", EMAIL);
		jdbcTemplate.update("delete from users where email = ?", EMAIL);
	}

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
