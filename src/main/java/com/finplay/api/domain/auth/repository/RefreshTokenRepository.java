// Refresh Token 엔티티의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.domain.auth.repository;

import com.finplay.api.domain.auth.entity.RefreshToken;
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

	// 이메일 변경 확인 성공 시 회원의 기존 활성 Refresh Token을 전부 폐기한다 — 만료 여부와 무관하게 이력을 남긴다.
	@Modifying
	@Query("""
		UPDATE RefreshToken refreshToken
		SET refreshToken.revokedAt = :now
		WHERE refreshToken.user.id = :userId
			AND refreshToken.revokedAt IS NULL
		""")
	int revokeAllActiveByUserId(@Param("userId")
	Long userId, @Param("now")
	LocalDateTime now);
}
