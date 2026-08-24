// V46 마이그레이션(튜토리얼 계좌 신설 + 배포 시점 잔존 샌드박스 지정가 매수 PENDING 정리)의 UPDATE 문을
// 실제 마이그레이션 파일에서 읽어 재실행하는 방식으로 검증한다 (047-tutorial-sandbox-cash-isolation,
// tasks.md 1번, 이슈 #450, TUTORIAL-CASH-ISOL-008). Flyway는 컨텍스트 기동 시 이미 빈 테이블에 V46을
// 적용해 두므로, 이 테스트는 "마이그레이션 적용 전 오염된 상태"를 흉내낸 데이터를 심은 뒤 같은 UPDATE
// 문을 다시 실행해 배포 시점 백필과 그 멱등성을 시뮬레이션한다.
//
// cash_balance·sandbox_cash_adjustment를 다루는 첫 UPDATE는 이슈 #459 PR-B(V47)로 그 컬럼 자체가
// 삭제되면서 재실행 대상에서 제외했다 — 이제 아래에 남은 두 UPDATE(예약 현금 반환·PENDING 매수 취소)만
// 재현한다. 그 UPDATE의 회귀 커버리지(계좌 현금 원복·완료 보상 제외·0원 하한)는 V47 배포로 컬럼이
// 사라지면서 함께 무효화됐다.
package com.finplay.api.domain.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TutorialAccountBackfillMigrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);
	private static final String MIGRATION_PATH = "/db/migration/V46__create_tutorial_accounts_and_backfill_cash.sql";

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	// jdbcTemplate의 UPDATE는 영속성 컨텍스트를 거치지 않고 DB를 직접 바꾼다 — 이미 로드된 Account가
	// 1차 캐시에 남아 있으면 findById가 갱신 전 값을 그대로 돌려주므로, 재조회 전에 반드시 비운다.
	private void runBackfillUpdate() {
		entityManager.flush();
		for (String statement : readBackfillUpdateStatements()) {
			jdbcTemplate.execute(statement);
		}
		entityManager.clear();
	}

	private List<String> readBackfillUpdateStatements() {
		try {
			String sql = StreamUtils.copyToString(
				new ClassPathResource(MIGRATION_PATH).getInputStream(), StandardCharsets.UTF_8);
			// SQL 줄 주석(--)을 먼저 제거해야 CREATE TABLE 문 앞에 붙은 여러 줄 주석이 같은 세미콜론
			// 구간에 섞여 startsWith("UPDATE") 판별을 방해하지 않는다. CREATE TABLE은 Flyway가 컨텍스트
			// 기동 시 이미 적용해 뒀으므로 재실행하면 "table already exists" 오류가 난다 — UPDATE만 골라낸다.
			// sandbox_cash_adjustment를 다루는 UPDATE는 V47이 그 컬럼을 삭제해 재실행하면 "Unknown column"
			// 오류가 나므로 제외한다.
			String withoutComments = Arrays.stream(sql.split("\n"))
				.filter(line -> !line.trim().startsWith("--"))
				.reduce("", (a, b) -> a + "\n" + b);
			return Arrays.stream(withoutComments.split(";"))
				.map(String::trim)
				.filter(statement -> !statement.isEmpty())
				.filter(statement -> statement.toUpperCase().startsWith("UPDATE"))
				.filter(statement -> !statement.toLowerCase().contains("sandbox_cash_adjustment"))
				.toList();
		} catch (IOException e) {
			throw new IllegalStateException("V46 마이그레이션 파일을 읽을 수 없습니다.", e);
		}
	}

	@Test
	@DisplayName("배포 시점에 이미 존재하는 샌드박스 지정가 매수 PENDING 주문은 취소되고 실제 계좌 예약이 반환된다")
	void backfillCancelsLegacyPendingSandboxBuyOrdersAndReturnsRealAccountReservation() {
		// PR #452 리뷰 권장 1번: 047 이전 코드가 실제 계좌에 현금을 예약해 만든 PENDING 지정가 매수를 그대로
		// 재현한다 — 정리하지 않으면 TutorialLegacyPendingOrderPostMigrationIntegrationTest가 재현한 대로
		// 체결·취소 모두 IllegalStateException으로 영구히 막힌다.
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-legacy-order@finplay.com", "hash", "v46legacy", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
		Instrument instrument = createTutorialSampleCryptoInstrument("v46-legacy-buy");
		account.reserveCash(100_050L);
		accountRepository.saveAndFlush(account);
		Order legacyBuy = orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("1000000"),
			"v46-legacy-buy-" + UUID.randomUUID(), "a".repeat(64), NOW));

		runBackfillUpdate();

		assertThat(orderRepository.findById(legacyBuy.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELLED);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash()).isZero();
	}

	@Test
	@DisplayName("샌드박스 지정가 매도 PENDING 주문은 현금 예약이 아니라 holdings.reservedQuantity를 쓰므로 백필 대상이 아니다")
	void backfillDoesNotTouchPendingSandboxSellOrders() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-legacy-sell@finplay.com", "hash", "v46legacysell", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
		Instrument instrument = createTutorialSampleCryptoInstrument("v46-legacy-sell");
		Order legacySell = orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.SELL, new BigDecimal("0.1"), new BigDecimal("1000000"),
			"v46-legacy-sell-" + UUID.randomUUID(), "b".repeat(64), NOW));

		runBackfillUpdate();

		assertThat(orderRepository.findById(legacySell.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash()).isZero();
	}

	private Instrument createTutorialSampleCryptoInstrument(String scenario) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + UUID.randomUUID().toString().substring(0, 8),
			scenario, BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrumentRepository.saveAndFlush(instrument);
	}
}
