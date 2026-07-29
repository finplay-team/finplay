// 계좌별 활성 보유 조회 쿼리 메서드를 검증하는 슬라이스 테스트 (docs/specs/006-portfolio-query, 이슈 #81)
package com.finplay.api.portfolio.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.portfolio.domain.Holding;
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
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
	}

	@Test
	@DisplayName("다른 계좌의 보유는 제외하고 본인 계좌의 활성 보유만 반환한다")
	void returnsOnlyOwnAccountActiveHoldings() {
		User other = userRepository.saveAndFlush(User.create("holding-other@finplay.com", "hash", "other", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, com.finplay.api.account.domain.Market.STOCK, NOW));

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
}
