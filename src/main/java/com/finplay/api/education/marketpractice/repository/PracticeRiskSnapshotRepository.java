// 튜토리얼 attempt의 실행 세대별 불변 위험 스냅샷 영속을 담당하는 JPA 리포지터리
package com.finplay.api.education.marketpractice.repository;

import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeRiskSnapshotRepository extends JpaRepository<PracticeRiskSnapshot, Long> {

	Optional<PracticeRiskSnapshot> findByAttemptIdAndRunNumber(Long attemptId, long runNumber);
}
