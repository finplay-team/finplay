// 사용자·시장별 튜토리얼 attempt 조회와 직렬화 잠금을 담당하는 JPA 리포지터리
package com.finplay.api.education.marketpractice.repository;

import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.market.domain.Market;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PracticeAttemptRepository extends JpaRepository<PracticeAttempt, Long> {

	Optional<PracticeAttempt> findByUserIdAndMarket(Long userId, Market market);

	@Modifying
	@Query(value = """
		INSERT IGNORE INTO practice_attempts
			(user_id, market, run_number, status, created_at, updated_at)
		VALUES (:userId, :market, 1, 'SELECTING_INSTRUMENT', :now, :now)
		""", nativeQuery = true)
	int insertIfAbsent(
		@Param("userId")
		Long userId, @Param("market")
		String market, @Param("now")
		java.time.LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM PracticeAttempt a WHERE a.userId = :userId AND a.market = :market")
	Optional<PracticeAttempt> findByUserIdAndMarketForUpdate(
		@Param("userId")
		Long userId, @Param("market")
		Market market);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM PracticeAttempt a WHERE a.id = :id")
	Optional<PracticeAttempt> findByIdForUpdate(@Param("id")
	Long id);
}
