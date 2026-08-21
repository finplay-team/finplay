// 시장가/지정가 매매 기반 실습 3단계 자유 복기의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.domain.education.marketpractice.repository;

import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeMarketReflectionRepository extends JpaRepository<PracticeMarketReflection, Long> {

	Optional<PracticeMarketReflection> findByUserIdAndTutorialKey(Long userId, String tutorialKey);
}
