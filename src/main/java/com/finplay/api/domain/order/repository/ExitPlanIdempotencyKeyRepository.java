package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.ExitPlanIdempotencyKey;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExitPlanIdempotencyKeyRepository extends JpaRepository<ExitPlanIdempotencyKey, Long> {

	Optional<ExitPlanIdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
