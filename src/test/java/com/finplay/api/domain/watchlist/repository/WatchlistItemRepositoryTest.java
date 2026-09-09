package com.finplay.api.domain.watchlist.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.watchlist.entity.WatchlistItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class WatchlistItemRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private WatchlistItemRepository watchlistItemRepository;

	private User user;
	private Instrument stockInstrument;
	private Instrument cryptoInstrument;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("watcher@finplay.com", "password-hash", "watcher", NOW));
		stockInstrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TSTSTK", "테스트주식", BigDecimal.ONE, 1000L, true, NOW));
		cryptoInstrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, "TSTCOIN", "테스트코인", BigDecimal.ONE, 1000L, true, NOW));
	}

	@Test
	void savingDuplicateUserAndInstrumentViolatesUniqueConstraint() {
		watchlistItemRepository.saveAndFlush(WatchlistItem.create(user.getId(), stockInstrument, NOW));

		assertThatThrownBy(() -> watchlistItemRepository
			.saveAndFlush(WatchlistItem.create(user.getId(), stockInstrument, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void findByUserIdAndInstrumentMarketReturnsOnlyMatchingMarketItems() {
		watchlistItemRepository.saveAndFlush(WatchlistItem.create(user.getId(), stockInstrument, NOW));
		watchlistItemRepository.saveAndFlush(WatchlistItem.create(user.getId(), cryptoInstrument, NOW.plusMinutes(1)));

		List<WatchlistItem> result = watchlistItemRepository
			.findByUserIdAndInstrument_MarketOrderByCreatedAtDescIdDesc(user.getId(), Market.CRYPTO);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getInstrument().getId()).isEqualTo(cryptoInstrument.getId());
	}

	@Test
	void findByUserIdOrderByCreatedAtDescIdDescReturnsItemsInLatestFirstOrder() {
		WatchlistItem older = watchlistItemRepository
			.saveAndFlush(WatchlistItem.create(user.getId(), stockInstrument, NOW));
		WatchlistItem newer = watchlistItemRepository
			.saveAndFlush(WatchlistItem.create(user.getId(), cryptoInstrument, NOW.plusMinutes(1)));

		List<WatchlistItem> result = watchlistItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId());

		assertThat(result).extracting(WatchlistItem::getId)
			.containsExactly(newer.getId(), older.getId());
	}

	@Test
	void findByUserIdOrderByCreatedAtDescIdDescBreaksSameTimestampTieByIdDescending() {
		WatchlistItem first = watchlistItemRepository
			.saveAndFlush(WatchlistItem.create(user.getId(), stockInstrument, NOW));
		WatchlistItem second = watchlistItemRepository
			.saveAndFlush(WatchlistItem.create(user.getId(), cryptoInstrument, NOW));

		List<WatchlistItem> result = watchlistItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId());

		assertThat(result).extracting(WatchlistItem::getId)
			.containsExactly(second.getId(), first.getId());
	}

	@Test
	void findByUserIdAndInstrumentIdReturnsTheMatchingItem() {
		WatchlistItem saved = watchlistItemRepository
			.saveAndFlush(WatchlistItem.create(user.getId(), stockInstrument, NOW));

		Optional<WatchlistItem> result = watchlistItemRepository
			.findByUserIdAndInstrumentId(user.getId(), stockInstrument.getId());

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(saved.getId());
	}

	@Test
	void findByUserIdAndInstrumentIdReturnsEmptyWhenNoMatchingItem() {
		Optional<WatchlistItem> result = watchlistItemRepository
			.findByUserIdAndInstrumentId(user.getId(), stockInstrument.getId());

		assertThat(result).isEmpty();
	}

	@Test
	void existsByUserIdAndInstrumentIdReturnsTrueWhenAlreadyRegistered() {
		watchlistItemRepository.saveAndFlush(WatchlistItem.create(user.getId(), stockInstrument, NOW));

		assertThat(watchlistItemRepository.existsByUserIdAndInstrumentId(user.getId(), stockInstrument.getId()))
			.isTrue();
		assertThat(watchlistItemRepository.existsByUserIdAndInstrumentId(user.getId(), cryptoInstrument.getId()))
			.isFalse();
	}
}
