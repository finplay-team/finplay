// V46 마이그레이션(튜토리얼 계좌 신설 + sandbox_cash_adjustment 백필)의 백필 UPDATE 문을 실제
// 마이그레이션 파일에서 읽어 재실행하는 방식으로 검증한다 (047-tutorial-sandbox-cash-isolation,
// tasks.md 1번, 이슈 #450, TUTORIAL-CASH-ISOL-008). Flyway는 컨텍스트 기동 시 이미 빈 테이블에 V46을
// 적용해 두므로, 이 테스트는 "마이그레이션 적용 전 오염된 상태"를 흉내낸 데이터를 심은 뒤 같은 UPDATE
// 문을 다시 실행해 배포 시점 백필과 그 멱등성을 시뮬레이션한다.
package com.finplay.api.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
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
			String withoutComments = Arrays.stream(sql.split("\n"))
				.filter(line -> !line.trim().startsWith("--"))
				.reduce("", (a, b) -> a + "\n" + b);
			return Arrays.stream(withoutComments.split(";"))
				.map(String::trim)
				.filter(statement -> !statement.isEmpty())
				.filter(statement -> statement.toUpperCase().startsWith("UPDATE"))
				.toList();
		} catch (IOException e) {
			throw new IllegalStateException("V46 마이그레이션 파일을 읽을 수 없습니다.", e);
		}
	}

	@Test
	@DisplayName("sandbox_cash_adjustment로 오염된 계좌는 그만큼 cash_balance가 원복되고 adjustment는 0이 된다")
	void backfillRestoresContaminatedAccountCashBalance() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-contaminated@finplay.com", "hash", "v46contam", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		account.addSandboxCashAdjustment(1_988L);
		account.addCash(1_988L);
		accountRepository.saveAndFlush(account);

		runBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(result.getSandboxCashAdjustment()).isZero();
	}

	@Test
	@DisplayName("샌드박스 활동이 없던 계좌(adjustment=0)는 백필 이후에도 cash_balance가 변하지 않는다")
	void backfillLeavesCleanAccountUnchanged() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-clean@finplay.com", "hash", "v46clean", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		account.deductCash(2_000_000L);
		accountRepository.saveAndFlush(account);

		runBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getCashBalance()).isEqualTo(8_000_000L);
		assertThat(result.getSandboxCashAdjustment()).isZero();
	}

	@Test
	@DisplayName("완료 보상으로 늘어난 조정값은 원복 대상에서 제외돼, 보상을 실제 매매에 쓴 계좌도 음수가 되지 않는다")
	void backfillExcludesCompletionRewardSoAccountThatSpentTheRewardDoesNotGoNegative() {
		// PR #452 리뷰 차단 2번 재현: 초기 1000만원 → STOCK 튜토리얼 완료 보상 500만원(adjustment도 함께
		// +500만원 누적, addSandboxCashAdjustment가 종목 조건 없이 항상 호출됐으므로) → 실제 주식을
		// 1200만원어치 매수해 현금 300만원. 옛 백필(adjustment 전액 차감)이면 300만원 - 500만원 = -200만원이
		// 되지만, 완료 보상분(500만원)은 원복 대상이 아니므로 차감량이 0이 돼 300만원 그대로여야 한다.
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-reward@finplay.com", "hash", "v46reward", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		account.addCash(5_000_000L);
		account.addSandboxCashAdjustment(5_000_000L);
		account.deductCash(12_000_000L);
		accountRepository.saveAndFlush(account);
		insertStockCompletionRecord(account, user, "INVESTMENT_PRACTICE_V1");

		runBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getCashBalance()).isEqualTo(3_000_000L);
		assertThat(result.getSandboxCashAdjustment()).isZero();
	}

	@Test
	@DisplayName("완료 보상 이외의 사유로 조정값이 남은 현금보다 커도 cash_balance는 0 밑으로 내려가지 않는다")
	void backfillNeverDrivesCashBalanceBelowZero() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-floor@finplay.com", "hash", "v46floor", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		// 완료 기록 없이(reward_component=0) adjustment가 남은 현금보다 크게 만든다 — 방어적 GREATEST(0, ...)
		// 없이는 8,000,000 - 9,000,000 = -1,000,000이 된다.
		account.addSandboxCashAdjustment(9_000_000L);
		account.deductCash(2_000_000L);
		accountRepository.saveAndFlush(account);

		runBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getCashBalance()).isZero();
		assertThat(result.getSandboxCashAdjustment()).isZero();
	}

	// practice_completions(→ practice_market_reflections → holdings → 시드 instrument)까지의 FK 체인을
	// 최소 값으로 채워, V46 백필의 LEFT JOIN이 "그 시장 튜토리얼을 완료한 사용자"로 인식하게 만든다.
	private void insertStockCompletionRecord(Account account, User user, String tutorialKey) {
		Long instrumentId = jdbcTemplate.queryForObject(
			"SELECT id FROM instruments WHERE symbol = '005930'", Long.class);
		jdbcTemplate.update(
			"INSERT INTO holdings (account_id, instrument_id, quantity, average_price, is_active, "
				+ "created_at, updated_at) VALUES (?, ?, 0, 0, false, ?, ?)",
			account.getId(), instrumentId, NOW, NOW);
		Long holdingId = jdbcTemplate.queryForObject(
			"SELECT id FROM holdings WHERE account_id = ? AND instrument_id = ?", Long.class,
			account.getId(), instrumentId);
		jdbcTemplate.update(
			"INSERT INTO practice_market_reflections (user_id, holding_id, tutorial_key, answer, created_at) "
				+ "VALUES (?, ?, ?, 'v46 backfill test fixture', ?)",
			user.getId(), holdingId, tutorialKey, NOW);
		Long reflectionId = jdbcTemplate.queryForObject(
			"SELECT id FROM practice_market_reflections WHERE user_id = ? AND tutorial_key = ?", Long.class,
			user.getId(), tutorialKey);
		jdbcTemplate.update(
			"INSERT INTO practice_completions (user_id, tutorial_key, reflection_id, completed_at) "
				+ "VALUES (?, ?, ?, ?)",
			user.getId(), tutorialKey, reflectionId, NOW);
	}

	@Test
	@DisplayName("백필 UPDATE를 두 번 실행해도(재실행 시뮬레이션) 같은 결과가 나온다 (TUTORIAL-CASH-ISOL-008 멱등성)")
	void backfillIsIdempotentAcrossReruns() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-idempotent@finplay.com", "hash", "v46idem", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		account.addSandboxCashAdjustment(5_012_000L);
		account.addCash(5_012_000L);
		accountRepository.saveAndFlush(account);

		runBackfillUpdate();
		Account firstRun = accountRepository.findById(account.getId()).orElseThrow();
		long firstCashBalance = firstRun.getCashBalance();
		long firstAdjustment = firstRun.getSandboxCashAdjustment();
		assertThat(firstCashBalance).isEqualTo(10_000_000L);
		assertThat(firstAdjustment).isZero();

		runBackfillUpdate();
		Account secondRun = accountRepository.findById(account.getId()).orElseThrow();

		assertThat(secondRun.getCashBalance()).isEqualTo(firstCashBalance);
		assertThat(secondRun.getSandboxCashAdjustment()).isEqualTo(firstAdjustment);
	}
}
