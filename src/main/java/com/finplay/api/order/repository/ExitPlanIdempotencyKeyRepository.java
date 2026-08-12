// OCO 예약 생성의 Idempotency-Key 매핑 영속을 담당하는 JPA 리포지터리
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.ExitPlanIdempotencyKey;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExitPlanIdempotencyKeyRepository extends JpaRepository<ExitPlanIdempotencyKey, Long> {

	// key-first 멱등 판정의 최초 조회(021 plan "멱등성"). unique 위반 폴백에서도 같은 메서드를 재사용한다.
	Optional<ExitPlanIdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
