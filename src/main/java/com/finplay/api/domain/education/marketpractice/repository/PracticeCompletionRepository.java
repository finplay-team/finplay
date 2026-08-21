// 시장가/지정가 매매 기반 실습 튜토리얼 완료 판정의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.domain.education.marketpractice.repository;

import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeCompletionRepository extends JpaRepository<PracticeCompletion, Long> {

	Optional<PracticeCompletion> findByUserIdAndTutorialKey(Long userId, String tutorialKey);
}
