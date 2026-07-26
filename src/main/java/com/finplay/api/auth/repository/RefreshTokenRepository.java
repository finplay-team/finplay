// Refresh Token 엔티티의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.auth.repository;

import com.finplay.api.auth.domain.RefreshToken;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	List<RefreshToken> findAllByTokenHash(String tokenHash);

	@Modifying
	@Query("""
		UPDATE RefreshToken refreshToken
		SET refreshToken.revokedAt = :now
		WHERE refreshToken.id = :id
			AND refreshToken.revokedAt IS NULL
			AND refreshToken.expiresAt > :now
		""")
	int revokeIfActiveAndNotExpired(@Param("id")
	Long id, @Param("now")
	LocalDateTime now);
}
