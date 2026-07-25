// users 테이블의 UNIQUE(email·nickname) 제약과 existsByEmail 실동작을 검증하는 슬라이스 테스트 (ADR-0003)
package com.finplay.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 25, 10, 30, 0);

	@Autowired
	private UserRepository userRepository;

	@Test
	@DisplayName("existsByEmail은 저장된 이메일이면 true, 없으면 false를 반환한다")
	void existsByEmailReflectsStoredRows() {
		userRepository.save(User.create("alice@finplay.com", "hash", "alice", NOW));

		assertThat(userRepository.existsByEmail("alice@finplay.com")).isTrue();
		assertThat(userRepository.existsByEmail("nobody@finplay.com")).isFalse();
	}

	@Test
	@DisplayName("이메일이 같으면 UNIQUE(email) 제약으로 저장이 거부된다")
	void duplicateEmailViolatesUniqueConstraint() {
		userRepository.saveAndFlush(User.create("dup@finplay.com", "hash", "nickA", NOW));

		User duplicateEmail = User.create("dup@finplay.com", "hash", "nickB", NOW);

		assertThatThrownBy(() -> userRepository.saveAndFlush(duplicateEmail))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("닉네임이 같으면 UNIQUE(nickname) 제약으로 저장이 거부된다")
	void duplicateNicknameViolatesUniqueConstraint() {
		userRepository.saveAndFlush(User.create("a@finplay.com", "hash", "sameNick", NOW));

		User duplicateNickname = User.create("b@finplay.com", "hash", "sameNick", NOW);

		assertThatThrownBy(() -> userRepository.saveAndFlush(duplicateNickname))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
