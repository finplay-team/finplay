package com.finplay.api.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.EmailChangeVerification;
import com.finplay.api.domain.auth.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class EmailChangeVerificationRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 10, 30, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EmailChangeVerificationRepository emailChangeVerificationRepository;

	@Test
	@DisplayName("저장하면 해시·만료·미소비 상태가 그대로 조회된다")
	void saveStoresHashExpiryAndUnconsumedState() {
		User user = saveUser("change-save@finplay.com", "change-save-user");

		EmailChangeVerification saved = emailChangeVerificationRepository.saveAndFlush(
			EmailChangeVerification.create(user, "new@finplay.com", "code-hash-value", NOW.plusMinutes(5), NOW));

		EmailChangeVerification found = emailChangeVerificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getUser().getId()).isEqualTo(user.getId());
		assertThat(found.getNewEmail()).isEqualTo("new@finplay.com");
		assertThat(found.getCodeHash()).isEqualTo("code-hash-value");
		assertThat(found.getAttemptCount()).isZero();
		assertThat(found.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(found.getLastSentAt()).isEqualTo(NOW);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("존재하지 않는 user_id로 저장하면 fk_email_change_verifications_user 위반으로 거부된다")
	void saveRejectsNonExistentUserIdViaForeignKeyConstraint() {
		User transientUser = User.create("change-fk@finplay.com", "password-hash", "change-fk-user", NOW);
		ReflectionTestUtils.setField(transientUser, "id", 999_999L);
		EmailChangeVerification orphan = EmailChangeVerification
			.create(transientUser, "new@finplay.com", "code-hash", NOW.plusMinutes(5), NOW);

		assertThatThrownBy(() -> emailChangeVerificationRepository.saveAndFlush(orphan))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("countByUserIdAndCreatedAtAfter는 같은 회원이면서 기준 시각 이후에 생성된 행만 센다")
	void countByUserIdAndCreatedAtAfterCountsOnlyMatchingRowsAfterBoundary() {
		User user = saveUser("change-count@finplay.com", "change-count-user");
		User otherUser = saveUser("change-count-other@finplay.com", "change-count-other-user");

		emailChangeVerificationRepository
			.save(newVerification(user, "a@finplay.com", NOW.minusSeconds(1)));
		emailChangeVerificationRepository.save(newVerification(user, "b@finplay.com", NOW));
		emailChangeVerificationRepository.save(newVerification(user, "c@finplay.com", NOW.plusSeconds(1)));
		emailChangeVerificationRepository.save(newVerification(user, "d@finplay.com", NOW.plusMinutes(10)));
		emailChangeVerificationRepository
			.save(newVerification(otherUser, "e@finplay.com", NOW.plusMinutes(10)));
		emailChangeVerificationRepository.flush();

		long count = emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(user.getId(), NOW);

		assertThat(count).isEqualTo(2);
	}

	@Test
	@DisplayName("무효화 대상 조회는 같은 회원·같은 새 이메일이면서 미소비·미만료인 행만 반환한다")
	void findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfterFiltersCorrectly() {
		User user = saveUser("change-find@finplay.com", "change-find-user");
		User otherUser = saveUser("change-find-other@finplay.com", "change-find-other-user");
		String targetEmail = "target@finplay.com";

		EmailChangeVerification matching = EmailChangeVerification
			.create(user, targetEmail, "hash", NOW.plusMinutes(5), NOW);
		emailChangeVerificationRepository.save(matching);

		emailChangeVerificationRepository
			.save(EmailChangeVerification.create(user, "other-email@finplay.com", "hash", NOW.plusMinutes(5), NOW));

		emailChangeVerificationRepository
			.save(EmailChangeVerification.create(user, targetEmail, "hash", NOW.minusMinutes(1), NOW));

		EmailChangeVerification consumed = EmailChangeVerification
			.create(user, targetEmail, "hash", NOW.plusMinutes(5), NOW);
		ReflectionTestUtils.setField(consumed, "consumedAt", NOW.minusMinutes(1));
		emailChangeVerificationRepository.save(consumed);

		emailChangeVerificationRepository
			.save(EmailChangeVerification.create(otherUser, targetEmail, "hash", NOW.plusMinutes(5), NOW));
		emailChangeVerificationRepository.flush();

		List<EmailChangeVerification> found = emailChangeVerificationRepository
			.findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter(user.getId(), targetEmail, NOW);

		assertThat(found).hasSize(1);
		assertThat(found.get(0).getId()).isEqualTo(matching.getId());
	}

	@Test
	@DisplayName("findFirstByUserIdAndNewEmailOrderByCreatedAtDesc는 재발송으로 여러 행이 쌓였을 때 최신 행만 반환한다")
	void findFirstByUserIdAndNewEmailOrderByCreatedAtDescReturnsLatestRowOnly() {
		User user = saveUser("change-latest@finplay.com", "change-latest-user");
		String targetEmail = "latest-target@finplay.com";

		EmailChangeVerification oldest = EmailChangeVerification
			.create(user, targetEmail, "hash-oldest", NOW.plusMinutes(5), NOW.minusMinutes(10));
		EmailChangeVerification latest = EmailChangeVerification
			.create(user, targetEmail, "hash-latest", NOW.plusMinutes(5), NOW);
		emailChangeVerificationRepository.save(oldest);
		emailChangeVerificationRepository.save(latest);
		emailChangeVerificationRepository
			.save(EmailChangeVerification.create(user, "other@finplay.com", "hash-other", NOW.plusMinutes(5), NOW));
		emailChangeVerificationRepository.flush();

		EmailChangeVerification found = emailChangeVerificationRepository
			.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(user.getId(), targetEmail)
			.orElseThrow();

		assertThat(found.getId()).isEqualTo(latest.getId());
	}

	@Test
	@DisplayName("findFirstByUserIdAndNewEmailOrderByCreatedAtDesc는 같은 새 이메일이라도 다른 회원의 행은 반환하지 않는다")
	void findFirstByUserIdAndNewEmailOrderByCreatedAtDescExcludesOtherUsersRowsWithSameNewEmail() {
		User user = saveUser("change-owner@finplay.com", "change-owner-user");
		User otherUser = saveUser("change-stranger@finplay.com", "change-stranger-user");
		String targetEmail = "shared-target@finplay.com";

		emailChangeVerificationRepository
			.save(EmailChangeVerification.create(otherUser, targetEmail, "hash-stranger", NOW.plusMinutes(5), NOW));
		emailChangeVerificationRepository.flush();

		Optional<EmailChangeVerification> found = emailChangeVerificationRepository
			.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(user.getId(), targetEmail);

		assertThat(found).isEmpty();
	}

	private static EmailChangeVerification newVerification(User user, String newEmail, LocalDateTime createdAt) {
		return EmailChangeVerification.create(user, newEmail, "code-hash", createdAt.plusMinutes(5), createdAt);
	}

	private User saveUser(String email, String nickname) {
		return userRepository.saveAndFlush(User.create(email, "password-hash", nickname, NOW));
	}
}
