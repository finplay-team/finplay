package com.finplay.api.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
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
	@DisplayName("existsByNicknameAndIdNot은 자기 자신의 닉네임은 중복으로 보지 않는다")
	void existsByNicknameAndIdNotReturnsFalseForOwnNickname() {
		User user = userRepository.saveAndFlush(User.create("own@finplay.com", "hash", "ownNick", NOW));

		assertThat(userRepository.existsByNicknameAndIdNot("ownNick", user.getId())).isFalse();
	}

	@Test
	@DisplayName("existsByNicknameAndIdNot은 다른 회원이 쓰는 닉네임이면 true를 반환한다")
	void existsByNicknameAndIdNotReturnsTrueForOtherUsersNickname() {
		User me = userRepository.saveAndFlush(User.create("me@finplay.com", "hash", "myNick", NOW));
		userRepository.saveAndFlush(User.create("other@finplay.com", "hash", "otherNick", NOW));

		assertThat(userRepository.existsByNicknameAndIdNot("otherNick", me.getId())).isTrue();
		assertThat(userRepository.existsByNicknameAndIdNot("nobodyNick", me.getId())).isFalse();
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
