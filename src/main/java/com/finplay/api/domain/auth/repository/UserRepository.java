// 회원 조회·중복 확인을 담당하는 JPA 리포지토리
package com.finplay.api.domain.auth.repository;

import com.finplay.api.domain.auth.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	boolean existsByNickname(String nickname);

	boolean existsByNicknameAndIdNot(String nickname, Long id);

	Optional<User> findByEmail(String email);
}
