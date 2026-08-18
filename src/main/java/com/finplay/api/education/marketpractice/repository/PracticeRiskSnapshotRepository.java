// 튜토리얼 attempt의 실행 세대별 불변 위험 스냅샷 영속을 담당하는 JPA 리포지터리
package com.finplay.api.education.marketpractice.repository;

import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeRiskSnapshotRepository extends JpaRepository<PracticeRiskSnapshot, Long> {

	// 화면의 "지금 내 기준선" — 그 실행 세대의 가장 최근 진입.
	Optional<PracticeRiskSnapshot> findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(
		Long attemptId, long runNumber);

	// 관찰 필터 기준선 — 그 실행 세대의 첫 진입. 최신 진입을 쓰면 재매수 순간 이전 관찰이 필터에서
	// 사라져 3단계가 미완료로 되돌아간다(이슈 #420과 같은 유형).
	Optional<PracticeRiskSnapshot> findByAttemptIdAndRunNumberAndEntrySequence(
		Long attemptId, long runNumber, int entrySequence);

	// 다음 진입의 entry_sequence 산출용 — 존재 여부가 아니라 개수가 필요하다.
	long countByAttemptIdAndRunNumber(Long attemptId, long runNumber);
}
