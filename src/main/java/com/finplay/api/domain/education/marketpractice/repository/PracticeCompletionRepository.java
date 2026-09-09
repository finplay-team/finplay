package com.finplay.api.domain.education.marketpractice.repository;

import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeCompletionRepository extends JpaRepository<PracticeCompletion, Long> {

	Optional<PracticeCompletion> findByUserIdAndTutorialKey(Long userId, String tutorialKey);
}
