// 동일 즐겨찾기 동시 등록이 실제 MySQL 유일 제약에서 한 행과 한 409로 수렴하는지 검증한다.
package com.finplay.api.favorite.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.repository.FavoriteRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FavoriteConcurrencyIntegrationTest {

	@Autowired
	private FavoriteService favoriteService;
	@Autowired
	private FavoriteRepository favoriteRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	private Long createdUserId;

	@AfterEach
	void cleanUpCreatedUser() {
		if (createdUserId == null) {
			return;
		}
		jdbcTemplate.update("DELETE FROM favorites WHERE user_id = ?", createdUserId);
		userRepository.deleteById(createdUserId);
	}

	@Test
	void concurrentCreateFavoritePersistsOneRowAndMapsLoserToConflict() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 8, 3, 10, 0);
		User user = userRepository.saveAndFlush(User.create(
			"favorite-race-163@finplay.com", "hash", "favorite-race-163", now));
		createdUserId = user.getId();
		Instrument instrument = instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK).get(0);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<Object> request = () -> {
			ready.countDown();
			start.await();
			try {
				return favoriteService.createFavorite(user.getId(), instrument.getId());
			} catch (BusinessException exception) {
				return exception.getErrorCode();
			}
		};

		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> first = executor.submit(request);
			Future<Object> second = executor.submit(request);
			ready.await();
			start.countDown();
			List<Object> results = List.of(first.get(), second.get());

			assertThat(results).filteredOn(FavoriteResponse.class::isInstance).hasSize(1);
			assertThat(results).filteredOn(ErrorCode.DUPLICATE_RESOURCE::equals).hasSize(1);
		} finally {
			executor.shutdownNow();
		}
		Long matchingFavoriteCount = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM favorites WHERE user_id = ? AND instrument_id = ?",
			Long.class, user.getId(), instrument.getId());
		assertThat(matchingFavoriteCount).isEqualTo(1L);
	}
}
