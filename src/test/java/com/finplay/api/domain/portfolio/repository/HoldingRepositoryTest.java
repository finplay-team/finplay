package com.finplay.api.domain.portfolio.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class HoldingRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	private Account ownerAccount;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		User owner = userRepository.saveAndFlush(User.create("holding-owner@finplay.com", "hash", "owner", NOW));
		ownerAccount = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
	}

	@Test
	@DisplayName("다른 계좌의 보유는 제외하고 본인 계좌의 활성 보유만 반환한다")
	void returnsOnlyOwnAccountActiveHoldings() {
		User other = userRepository.saveAndFlush(User.create("holding-other@finplay.com", "hash", "other", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, Market.STOCK, NOW));

		Holding ownerHolding = Holding.create(ownerAccount, instrument, NOW);
		ownerHolding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(ownerHolding);

		Holding otherHolding = Holding.create(otherAccount, instrument, NOW);
		otherHolding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(otherHolding);

		List<Holding> result = holdingRepository.findAllByAccountIdAndIsActiveTrue(ownerAccount.getId());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getId()).isEqualTo(ownerHolding.getId());
	}

	@Test
	@DisplayName("전량 매도해 isActive가 false인 보유는 제외한다")
	void excludesInactiveHoldingAfterFullSell() {
		Holding activeHolding = Holding.create(ownerAccount, instrument, NOW);
		activeHolding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(activeHolding);

		Instrument otherInstrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST02", "테스트종목2", BigDecimal.valueOf(200), 10_000L, true, NOW));
		Holding soldOutHolding = Holding.create(ownerAccount, otherInstrument, NOW);
		soldOutHolding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		soldOutHolding.applySell(BigDecimal.TEN, NOW);
		holdingRepository.saveAndFlush(soldOutHolding);

		List<Holding> result = holdingRepository.findAllByAccountIdAndIsActiveTrue(ownerAccount.getId());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getId()).isEqualTo(activeHolding.getId());
	}

	@Test
	@DisplayName("JOIN FETCH로 instrument를 함께 조회해 지연 로딩 예외 없이 접근할 수 있다")
	void findAllByAccountIdFetchesInstrumentWithoutLazyInitializationException() {
		Holding holding = Holding.create(ownerAccount, instrument, NOW);
		holding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(holding);
		entityManager.clear();

		List<Holding> result = holdingRepository.findAllByAccountIdAndIsActiveTrue(ownerAccount.getId());

		assertThat(result).extracting(h -> h.getInstrument().getSymbol())
			.containsExactly(instrument.getSymbol());
	}

	@Test
	@DisplayName("활성 보유가 없는 계좌는 빈 목록을 반환한다")
	void returnsEmptyListWhenNoActiveHoldings() {
		List<Holding> result = holdingRepository.findAllByAccountIdAndIsActiveTrue(ownerAccount.getId());

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("PR #97 리뷰 권장사항 1: 삽입 순서와 무관하게 종목 심볼 오름차순으로 반환한다")
	void returnsHoldingsOrderedBySymbolAscendingRegardlessOfInsertionOrder() {
		Instrument symbolC = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TESTC", "테스트종목C", BigDecimal.valueOf(300), 10_000L, true, NOW));
		Instrument symbolA = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TESTA", "테스트종목A", BigDecimal.valueOf(100), 10_000L, true, NOW));

		Holding holdingC = Holding.create(ownerAccount, symbolC, NOW);
		holdingC.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(holdingC);

		Holding holdingA = Holding.create(ownerAccount, symbolA, NOW);
		holdingA.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(holdingA);

		Holding holdingTest01 = Holding.create(ownerAccount, instrument, NOW);
		holdingTest01.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(holdingTest01);

		List<Holding> result = holdingRepository.findAllByAccountIdAndIsActiveTrue(ownerAccount.getId());

		assertThat(result).extracting(h -> h.getInstrument().getSymbol())
			.containsExactly("TEST01", "TESTA", "TESTC");
	}

	@Test
	@DisplayName("033-exclude-tutorial-sandbox-data(SANDBOX-EXCL-001): 튜토리얼 샌드박스 종목 보유는 결과에서 제외한다")
	void findAllByAccountIdAndIsActiveTrueExcludesTutorialSampleInstrumentHoldings() {
		Holding realHolding = Holding.create(ownerAccount, instrument, NOW);
		realHolding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(realHolding);

		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();
		Holding sandboxHolding = Holding.create(ownerAccount, sandboxInstrument, NOW);
		sandboxHolding.applyBuy(BigDecimal.ONE, new BigDecimal("10000"), NOW);
		holdingRepository.saveAndFlush(sandboxHolding);

		List<Holding> result = holdingRepository.findAllByAccountIdAndIsActiveTrue(ownerAccount.getId());

		assertThat(result).extracting(Holding::getId).containsExactly(realHolding.getId());
	}

	@Test
	@DisplayName("회귀: findByAccountIdAndInstrumentId(HoldingService.findHoldingId가 사용)는 "
		+ "튜토리얼 샌드박스 종목 보유도 여전히 찾는다 — 026/031 튜토리얼 chain 해석이 깨지면 안 된다")
	void findByAccountIdAndInstrumentIdStillFindsTutorialSampleInstrumentHoldingRegression() {
		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();
		Holding sandboxHolding = Holding.create(ownerAccount, sandboxInstrument, NOW);
		sandboxHolding.applyBuy(BigDecimal.ONE, new BigDecimal("10000"), NOW);
		holdingRepository.saveAndFlush(sandboxHolding);

		var result = holdingRepository.findByAccountIdAndInstrumentId(ownerAccount.getId(), sandboxInstrument.getId());

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(sandboxHolding.getId());
	}

	@Test
	@DisplayName("회귀: findById(HoldingService.findHoldingForOwner가 사용)는 "
		+ "튜토리얼 샌드박스 종목 보유도 여전히 찾는다 — 026 3단계 관찰 API가 깨지면 안 된다")
	void findByIdStillFindsTutorialSampleInstrumentHoldingRegression() {
		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		Holding sandboxHolding = Holding.create(ownerAccount, sandboxInstrument, NOW);
		sandboxHolding.applyBuy(BigDecimal.ONE, new BigDecimal("10000"), NOW);
		holdingRepository.saveAndFlush(sandboxHolding);

		var result = holdingRepository.findById(sandboxHolding.getId());

		assertThat(result).isPresent();
		assertThat(result.get().getInstrument().isTutorialSample()).isTrue();
	}

	@Test
	@DisplayName("계좌·종목 조합으로 락 조회하면 해당 보유가 반환된다 (015-limit-order LMT-001·LMT-002)")
	void findByAccountIdAndInstrumentIdForUpdateReturnsMatchingHolding() {
		Holding holding = Holding.create(ownerAccount, instrument, NOW);
		holding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(holding);

		var result = holdingRepository.findByAccountIdAndInstrumentIdForUpdate(ownerAccount.getId(),
			instrument.getId());

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(holding.getId());
	}

	@Test
	@DisplayName("계좌·종목 조합이 없으면 빈 값을 반환한다")
	void findByAccountIdAndInstrumentIdForUpdateReturnsEmptyWhenNotFound() {
		var result = holdingRepository.findByAccountIdAndInstrumentIdForUpdate(ownerAccount.getId(), 999_999L);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("054-limit-order-fill-bulk-lock: 계좌 목록 벌크 락 조회는 입력 순서와 무관하게 holding ID 오름차순으로 반환한다")
	void findByAccountIdInAndInstrumentIdForUpdateReturnsHoldingsInAscendingIdOrder() {
		User other = userRepository.saveAndFlush(
			User.create("holding-bulk-other@finplay.com", "hash", "bulkother", NOW));
		Account otherAccount = accountRepository.saveAndFlush(Account.create(other, Market.STOCK, NOW));

		Holding ownerHolding = Holding.create(ownerAccount, instrument, NOW);
		ownerHolding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(ownerHolding);

		Holding otherHolding = Holding.create(otherAccount, instrument, NOW);
		otherHolding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(otherHolding);

		List<Holding> result = holdingRepository.findByAccountIdInAndInstrumentIdForUpdate(
			List.of(otherAccount.getId(), ownerAccount.getId()), instrument.getId());

		assertThat(result).extracting(Holding::getId)
			.containsExactly(ownerHolding.getId(), otherHolding.getId());
	}

	@Test
	@DisplayName("054-limit-order-fill-bulk-lock: 계좌 목록에 보유가 없는 계좌가 섞여 있어도 예외 없이 조용히 빠진다")
	void findByAccountIdInAndInstrumentIdForUpdateSilentlyDropsAccountsWithoutHolding() {
		User other = userRepository.saveAndFlush(
			User.create("holding-bulk-empty@finplay.com", "hash", "bulkempty", NOW));
		Account otherAccount = accountRepository.saveAndFlush(Account.create(other, Market.STOCK, NOW));

		Holding ownerHolding = Holding.create(ownerAccount, instrument, NOW);
		ownerHolding.applyBuy(BigDecimal.TEN, new BigDecimal("50000"), NOW);
		holdingRepository.saveAndFlush(ownerHolding);

		List<Holding> result = holdingRepository.findByAccountIdInAndInstrumentIdForUpdate(
			List.of(ownerAccount.getId(), otherAccount.getId()), instrument.getId());

		assertThat(result).extracting(Holding::getId).containsExactly(ownerHolding.getId());
	}
}
