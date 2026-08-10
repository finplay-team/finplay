// 코인 튜토리얼 가상 가격 세션의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.education.priceruntime.repository;

import com.finplay.api.education.priceruntime.domain.PracticePriceSession;
import com.finplay.api.education.priceruntime.domain.PracticePriceSessionStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticePriceSessionRepository extends JpaRepository<PracticePriceSession, Long> {

	Optional<PracticePriceSession> findByIdAndUserId(Long id, Long userId);

	boolean existsByUserIdAndInstrumentIdAndStatus(Long userId, Long instrumentId, PracticePriceSessionStatus status);
}
