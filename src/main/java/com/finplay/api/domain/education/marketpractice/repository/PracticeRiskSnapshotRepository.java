package com.finplay.api.domain.education.marketpractice.repository;

import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeRiskSnapshotRepository extends JpaRepository<PracticeRiskSnapshot, Long> {

	Optional<PracticeRiskSnapshot> findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(
		Long attemptId, long runNumber);

	Optional<PracticeRiskSnapshot> findByAttemptIdAndRunNumberAndEntrySequence(
		Long attemptId, long runNumber, int entrySequence);

	@EntityGraph(attributePaths = {"buyTrade", "buyTrade.order"})
	List<PracticeRiskSnapshot> findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(Long attemptId, long runNumber);

	long countByAttemptIdAndRunNumber(Long attemptId, long runNumber);

}
