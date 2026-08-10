// 코인 튜토리얼 가상 가격 세션의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.education.priceruntime.repository;

import com.finplay.api.education.priceruntime.domain.PracticePriceSession;
import com.finplay.api.education.priceruntime.domain.PracticePriceSessionStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PracticePriceSessionRepository extends JpaRepository<PracticePriceSession, Long> {

	Optional<PracticePriceSession> findByIdAndUserId(Long id, Long userId);

	boolean existsByUserIdAndInstrumentIdAndStatus(Long userId, Long instrumentId, PracticePriceSessionStatus status);

	// next-tick 진행용 소유자 스코프 비관 잠금 — id만으로 잠그면 타인 세션 행도 잠그게 되어
	// "없는 세션=타인 세션=404" 존재 은닉이 흐트러진다 (이슈 #319 코멘트 근거).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM PracticePriceSession s WHERE s.id = :id AND s.userId = :userId")
	Optional<PracticePriceSession> findByIdAndUserIdForUpdate(@Param("id")
	Long id, @Param("userId")
	Long userId);
}
