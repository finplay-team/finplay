package com.finplay.api.domain.auth.repository;

import com.finplay.api.domain.auth.entity.ReauthToken;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReauthTokenRepository extends JpaRepository<ReauthToken, Long> {

	@Modifying
	@Query("""
		UPDATE ReauthToken reauthToken
		SET reauthToken.consumedAt = :now
		WHERE reauthToken.tokenHash = :tokenHash
			AND reauthToken.user.id = :userId
			AND reauthToken.consumedAt IS NULL
			AND reauthToken.expiresAt > :now
		""")
	int consumeIfValidForUser(@Param("tokenHash")
	String tokenHash, @Param("userId")
	Long userId, @Param("now")
	LocalDateTime now);
}
