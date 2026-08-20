// 튜토리얼 attempt의 실행 세대별 불변 위험 스냅샷 영속을 담당하는 JPA 리포지터리
package com.finplay.api.education.marketpractice.repository;

import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeRiskSnapshotRepository extends JpaRepository<PracticeRiskSnapshot, Long> {

	// 화면의 "지금 내 기준선" — 그 실행 세대의 가장 최근 진입.
	Optional<PracticeRiskSnapshot> findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(
		Long attemptId, long runNumber);

	// 관찰 필터 기준선 — 그 실행 세대의 첫 진입. 최신 진입을 쓰면 재매수 순간 이전 관찰이 필터에서
	// 사라져 3단계가 미완료로 되돌아간다(이슈 #420과 같은 유형).
	Optional<PracticeRiskSnapshot> findByAttemptIdAndRunNumberAndEntrySequence(
		Long attemptId, long runNumber, int entrySequence);

	// 041 SCENARIO-019b — 완료 대조 배열은 그 실행 세대의 **모든** 진입을 진입 순서대로 본다.
	//
	// buyTrade와 그 order를 함께 가져온다. 둘 다 지연 로딩이라 없으면 진입 하나마다 조회가 두 번씩
	// 붙는데, 이 조회를 쓰는 진행 조회는 tick과 함께 폴링되는 경로다. order까지 넣는 이유는 진입 응답이
	// 그 매수의 주문 유형을 싣기 때문이다(이슈 #503).
	@EntityGraph(attributePaths = {"buyTrade", "buyTrade.order"})
	List<PracticeRiskSnapshot> findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(Long attemptId, long runNumber);

	// 다음 진입의 entry_sequence 산출용 — 존재 여부가 아니라 개수가 필요하다.
	long countByAttemptIdAndRunNumber(Long attemptId, long runNumber);

}
