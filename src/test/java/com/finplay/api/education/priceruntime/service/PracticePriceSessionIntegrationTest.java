// 실제 코인 종목·MySQL·Redis(PriceStore)를 사용해 가격 세션 생성·조회의 anchor 분기와 중복 생성 거부를
// 검증하는 통합 테스트다.
package com.finplay.api.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.priceruntime.domain.PracticePriceSessionStatus;
import com.finplay.api.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

// @Transactional은 JPA(MySQL) 쓰기만 롤백한다(PracticeHoldingObservationIntegrationTest와 동일 관례).
// Redis(price:crypto:*)는 롤백 대상이 아니라 @AfterEach에서 별도로 지운다.
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticePriceSessionIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);

	@Autowired
	private PracticePriceSessionService practicePriceSessionService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private PriceStore priceStore;
	@Autowired
	private StringRedisTemplate redisTemplate;
	@Autowired
	private TestClock clock;

	private final List<String> priceKeysToCleanUp = new ArrayList<>();

	@BeforeEach
	void setUp() {
		clock.set(NOW);
	}

	@AfterEach
	void tearDown() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceKeysToCleanUp.forEach(redisTemplate::delete);
	}

	@Test
	void createThenGetSessionUsesRealAvailablePriceAsAnchorAndMatchesOnRead() {
		User user = createUser("real-price");
		Instrument instrument = createCryptoInstrument("real-price");
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("54321.00000000"), NOW);

		PracticePriceSessionResponse created = practicePriceSessionService
			.createSession(user.getId(), instrument.getId());

		assertThat(created.startPrice()).isEqualByComparingTo("54321.00000000");
		assertThat(created.currentPrice()).isEqualByComparingTo("54321.00000000");
		assertThat(created.currentTick()).isZero();
		assertThat(created.status()).isEqualTo(PracticePriceSessionStatus.ACTIVE);

		PracticePriceSessionResponse fetched = practicePriceSessionService
			.getSession(user.getId(), created.sessionId());

		assertThat(fetched).isEqualTo(created);
	}

	@Test
	void createSessionFallsBackToTenThousandWhenRealPriceIsUnavailable() {
		User user = createUser("no-price");
		Instrument instrument = createCryptoInstrument("no-price");
		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);

		PracticePriceSessionResponse created = practicePriceSessionService
			.createSession(user.getId(), instrument.getId());

		assertThat(created.startPrice()).isEqualByComparingTo("10000.00000000");
		assertThat(created.currentPrice()).isEqualByComparingTo("10000.00000000");
	}

	@Test
	void secondActiveSessionAttemptForSameUserAndInstrumentIsRejectedWithConflict() {
		User user = createUser("dup-session");
		Instrument instrument = createCryptoInstrument("dup-session");
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100.00000000"), NOW);

		practicePriceSessionService.createSession(user.getId(), instrument.getId());

		assertThatThrownBy(() -> practicePriceSessionService.createSession(user.getId(), instrument.getId()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_SESSION_ALREADY_ACTIVE));
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "hash", uniqueNickname(scenario), NOW));
	}

	private Instrument createCryptoInstrument(String scenario) {
		String symbol = "PPS" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, scenario + "코인", new BigDecimal("0.00000001"), 0L, true, NOW));
		priceKeysToCleanUp.add("price:crypto:" + symbol);
		return instrument;
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}
}
