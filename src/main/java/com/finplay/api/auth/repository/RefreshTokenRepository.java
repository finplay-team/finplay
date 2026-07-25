// Refresh Token 엔티티의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.auth.repository;

import com.finplay.api.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
}
