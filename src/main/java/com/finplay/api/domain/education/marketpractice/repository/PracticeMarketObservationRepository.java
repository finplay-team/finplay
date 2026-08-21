// 시장가/지정가 매매 기반 실습 3단계 가격 관찰의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.domain.education.marketpractice.repository;

import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeMarketObservationRepository extends JpaRepository<PracticeMarketObservation, Long> {

	List<PracticeMarketObservation> findByUserIdAndHoldingIdOrderByObservedAtAsc(Long userId, Long holdingId);

	List<PracticeMarketObservation> findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(Long userId, Long holdingId);
}
