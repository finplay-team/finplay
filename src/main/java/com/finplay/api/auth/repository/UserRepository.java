// 회원 조회·중복 확인을 담당하는 JPA 리포지토리
package com.finplay.api.auth.repository;

import com.finplay.api.auth.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	boolean existsByNickname(String nickname);

	Optional<User> findByEmail(String email);
}
