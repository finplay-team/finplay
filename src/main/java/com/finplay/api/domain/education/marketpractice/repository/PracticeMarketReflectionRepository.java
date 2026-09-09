package com.finplay.api.domain.education.marketpractice.repository;

import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeMarketReflectionRepository extends JpaRepository<PracticeMarketReflection, Long> {

	Optional<PracticeMarketReflection> findByUserIdAndTutorialKey(Long userId, String tutorialKey);
}
