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

	// INSERT IGNORE가 아니라 ON DUPLICATE KEY UPDATE다 (이슈 #491). 두 구문 모두 중복이면 행을 바꾸지
	// 않지만 중복 키 검사에서 잡는 잠금이 다르다 — INSERT IGNORE는 uk_practice_attempts_user_market
	// 레코드에 **공유 잠금(S)** 을 잡고, 곧바로 뒤따르는 findByUserIdAndMarketForUpdate가 같은 레코드에
	// X를 요구한다. S는 여러 트랜잭션이 동시에 쥘 수 있으므로 동시 진입 2건이 서로의 S를 기다리는 잠금
	// 승격 교착이 되어 한쪽이 500으로 끝났다(실제 SHOW ENGINE INNODB STATUS로 확인한 교착 쌍이다.
	// 두 트랜잭션 모두 `lock mode S`를 HOLD한 채 `lock_mode X locks rec but not gap`을 WAIT했다).
	// ON DUPLICATE KEY UPDATE는 같은 자리에서 처음부터 **배타 잠금(X)** 을 잡아 두 트랜잭션이 이 구문에서
	// 직렬화되므로 승격 자체가 없다. 갭 잠금이 아니라 레코드 잠금이라 격리수준을 낮춰도(ADR-0028의
	// READ COMMITTED) 해결되지 않는 형태여서 구문 자체를 바꾼다.
	//
	// PracticeProgressRepository.insertIfAbsent가 같은 insert→FOR UPDATE 패턴에 이미 쓰고 있는 구문과
	// 같다. `user_id = user_id`는 값을 바꾸지 않는 대입이라 updated_at을 건드리지 않고, 삽입했을 때만
	// affected rows가 1이므로 호출부의 "이번에 만들었는가" 판정도 그대로다.
	@Modifying
	@Query(value = """
		INSERT INTO practice_attempts
			(user_id, market, run_number, status, created_at, updated_at)
		VALUES (:userId, :market, 1, 'SELECTING_INSTRUMENT', :now, :now)
		ON DUPLICATE KEY UPDATE user_id = user_id
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
