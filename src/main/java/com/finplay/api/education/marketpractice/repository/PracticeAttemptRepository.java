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

	// **이 구문은 이제 잠금 조회가 행을 찾지 못했을 때만 불린다** (이슈 #491, PracticeAttemptService
	// .ensureAttempt). 예전에는 진입마다 무조건 먼저 쏘고 같은 행을 FOR UPDATE로 다시 잠갔는데, 그
	// 순서가 교착의 원인이었다 — INSERT IGNORE는 중복 키 검사에서 uk_practice_attempts_user_market
	// 레코드에 **공유 잠금(S)** 을 남기고, 뒤따르는 findByUserIdAndMarketForUpdate가 같은 레코드에 X를
	// 요구한다. S는 여러 트랜잭션이 동시에 쥘 수 있어 동시 진입 2건이 서로의 S를 기다리는 승격 교착이
	// 됐다(SHOW ENGINE INNODB STATUS로 확인한 교착 쌍 — 둘 다 `lock mode S`를 HOLD한 채
	// `lock_mode X locks rec but not gap`을 WAIT했다). 갭 잠금이 아니라 레코드 잠금이라 격리수준을
	// 낮추는 것(ADR-0028)만으로는 해결되지 않는 형태였다.
	//
	// INSERT IGNORE가 아니라 ON DUPLICATE KEY UPDATE인 것은 남은 경합 구간 때문이다. 행이 없어 이
	// 구문에 도달한 동시 진입 2건 중 뒤에 온 쪽은 중복 키 검사에서 멈추는데, 여기서 S를 잡으면 그
	// 뒤의 FOR UPDATE가 다시 S→X 승격이 된다. ON DUPLICATE KEY UPDATE는 처음부터 배타 잠금(X)을 잡아
	// 그 구간에서도 승격이 생기지 않는다.
	//
	// PracticeProgressRepository.insertIfAbsent가 같은 insert→FOR UPDATE 패턴에 이미 쓰고 있는 구문과
	// 같다. `user_id = user_id`는 값을 바꾸지 않는 대입이라 updated_at을 건드리지 않는다.
	//
	// **반환값을 두지 않는 것이 이 구문의 조건이다.** MySQL Connector/J는 기본값(`useAffectedRows=false`,
	// 즉 CLIENT_FOUND_ROWS)에서 변경된 행이 아니라 **일치한 행**을 돌려주므로, ON DUPLICATE KEY UPDATE는
	// 중복이라 아무것도 바꾸지 않아도 1을 돌려준다 — INSERT IGNORE의 0과 다르다. 이 값을 "이번에
	// 만들었는가"로 읽으면 기존 행까지 새로 만든 것으로 오판한다(실제로 그렇게 회귀시켰고
	// LegacyPracticeCompletionAttemptCompatibilityIntegrationTest가 잡았다). 호출부는 이 값 대신
	// 삽입 전에 잠금 조회가 행을 찾았는지로 판정한다.
	@Modifying
	@Query(value = """
		INSERT INTO practice_attempts
			(user_id, market, run_number, status, created_at, updated_at)
		VALUES (:userId, :market, 1, 'SELECTING_INSTRUMENT', :now, :now)
		ON DUPLICATE KEY UPDATE user_id = user_id
		""", nativeQuery = true)
	void insertIfAbsent(
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
