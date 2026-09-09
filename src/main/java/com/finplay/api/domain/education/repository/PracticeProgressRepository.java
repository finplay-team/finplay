package com.finplay.api.domain.education.repository;

import com.finplay.api.domain.education.entity.PracticeProgress;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PracticeProgressRepository extends JpaRepository<PracticeProgress, Long> {

	@Modifying
	@Query(value = """
		INSERT INTO practice_progresses (user_id, tutorial_key, status, started_at, completed_at)
		VALUES (:userId, :tutorialKey, 'IN_PROGRESS', :startedAt, NULL)
		ON DUPLICATE KEY UPDATE id = id
		""", nativeQuery = true)
	void insertIfAbsent(
		@Param("userId")
		Long userId,
		@Param("tutorialKey")
		String tutorialKey,
		@Param("startedAt")
		LocalDateTime startedAt);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select progress
		from PracticeProgress progress
		where progress.user.id = :userId
			and progress.tutorialKey = :tutorialKey
		""")
	Optional<PracticeProgress> findByUserIdAndTutorialKeyForUpdate(
		@Param("userId")
		Long userId,
		@Param("tutorialKey")
		String tutorialKey);
}
