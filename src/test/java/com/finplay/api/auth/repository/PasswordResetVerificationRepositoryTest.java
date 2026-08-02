// password_reset_verifications 저장(발송 행·거부 행)·거부 행 포함 집계·거부 행 제외 무효화 대상 조회를 실제 MySQL로 검증하는 슬라이스 테스트다.
package com.finplay.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.PasswordResetVerification;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class PasswordResetVerificationRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 10, 30, 0);

	@Autowired
	private PasswordResetVerificationRepository passwordResetVerificationRepository;

	@Test
	@DisplayName("발송 행을 저장하면 해시·만료·발송시각·미소비 상태가 그대로 조회된다")
	void saveStoresHashExpiryAndUnconsumedState() {
		PasswordResetVerification saved = passwordResetVerificationRepository.saveAndFlush(
			PasswordResetVerification.create("reset-save@finplay.com", "code-hash-value", NOW.plusMinutes(5), NOW));

		PasswordResetVerification found = passwordResetVerificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getEmail()).isEqualTo("reset-save@finplay.com");
		assertThat(found.getCodeHash()).isEqualTo("code-hash-value");
		assertThat(found.getAttemptCount()).isZero();
		assertThat(found.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(found.getLastSentAt()).isEqualTo(NOW);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("거부 행은 user_id 없이 code_hash·expires_at·last_sent_at이 모두 NULL인 채로 저장된다")
	void saveRejectedStoresRowWithNullCodeColumns() {
		PasswordResetVerification saved = passwordResetVerificationRepository
			.saveAndFlush(PasswordResetVerification.createRejected("reset-rejected@finplay.com", NOW));

		PasswordResetVerification found = passwordResetVerificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getEmail()).isEqualTo("reset-rejected@finplay.com");
		assertThat(found.getCodeHash()).isNull();
		assertThat(found.getExpiresAt()).isNull();
		assertThat(found.getLastSentAt()).isNull();
		assertThat(found.getConsumedAt()).isNull();
		assertThat(found.getAttemptCount()).isZero();
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("countByEmailAndCreatedAtAfter는 미가입·소셜 전용으로 거부된 행까지 포함해 센다")
	void countByEmailAndCreatedAtAfterIncludesRejectedRows() {
		String email = "reset-count-rejected@finplay.com";

		// 발송 성공 1건 + 거부(404·409) 2건 — 결정 D4에 따라 셋 다 집계 대상이다.
		passwordResetVerificationRepository
			.save(PasswordResetVerification.create(email, "hash", NOW.plusMinutes(5), NOW.plusSeconds(1)));
		passwordResetVerificationRepository
			.save(PasswordResetVerification.createRejected(email, NOW.plusSeconds(2)));
		passwordResetVerificationRepository
			.save(PasswordResetVerification.createRejected(email, NOW.plusSeconds(3)));
		passwordResetVerificationRepository.flush();

		long count = passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(email, NOW);

		assertThat(count).isEqualTo(3);
	}

	@Test
	@DisplayName("countByEmailAndCreatedAtAfter는 기준 시각 이후·같은 이메일 행만 세고 경계값은 제외한다")
	void countByEmailAndCreatedAtAfterCountsOnlyMatchingRowsAfterBoundary() {
		String email = "reset-count@finplay.com";
		String otherEmail = "reset-count-other@finplay.com";

		// 기준 시각 이전 — 제외.
		passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, NOW.minusSeconds(1)));
		// 기준 시각과 동일 — after이므로 경계값은 제외.
		passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, NOW));
		// 기준 시각 이후 2건 — 포함.
		passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, NOW.plusSeconds(1)));
		passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, NOW.plusMinutes(10)));
		// 다른 이메일 — 제외 (제한은 이메일 단위다).
		passwordResetVerificationRepository
			.save(PasswordResetVerification.createRejected(otherEmail, NOW.plusMinutes(10)));
		passwordResetVerificationRepository.flush();

		long count = passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(email, NOW);

		assertThat(count).isEqualTo(2);
	}

	@Test
	@DisplayName("무효화 대상 조회는 거부 행(code_hash NULL)을 제외하고 실제 발송된 유효·미소비 행만 반환한다")
	void findInvalidationTargetsExcludesRejectedExpiredAndConsumedRows() {
		String email = "reset-find@finplay.com";
		String otherEmail = "reset-find-other@finplay.com";

		// 매칭: 실제 발송 + 미소비 + 유효.
		PasswordResetVerification matching = PasswordResetVerification.create(email, "hash", NOW.plusMinutes(5), NOW);
		passwordResetVerificationRepository.save(matching);

		// 제외: 거부 행 — code_hash가 NULL이라 무효화할 코드가 없다.
		passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, NOW));

		// 제외: 이미 만료.
		passwordResetVerificationRepository
			.save(PasswordResetVerification.create(email, "hash", NOW.minusMinutes(1), NOW));

		// 제외: 경계값 — expires_at이 기준 시각과 정확히 같으면 after가 아니다.
		passwordResetVerificationRepository.save(PasswordResetVerification.create(email, "hash", NOW, NOW));

		// 제외: 이미 소비됨.
		PasswordResetVerification consumed = PasswordResetVerification.create(email, "hash", NOW.plusMinutes(5), NOW);
		ReflectionTestUtils.setField(consumed, "consumedAt", NOW.minusMinutes(1));
		passwordResetVerificationRepository.save(consumed);

		// 제외: 다른 이메일.
		passwordResetVerificationRepository
			.save(PasswordResetVerification.create(otherEmail, "hash", NOW.plusMinutes(5), NOW));
		passwordResetVerificationRepository.flush();

		List<PasswordResetVerification> found = passwordResetVerificationRepository
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(email, NOW);

		assertThat(found).hasSize(1);
		assertThat(found.get(0).getId()).isEqualTo(matching.getId());
	}

	@Test
	@DisplayName("expire로 무효화한 이전 코드는 이후 무효화 대상 조회에서 빠져 유효한 코드가 최대 1개로 유지된다")
	void expiredRowIsExcludedFromLaterInvalidationLookup() {
		String email = "reset-resend@finplay.com";
		LocalDateTime resendAt = NOW.plusSeconds(70);

		PasswordResetVerification previous = passwordResetVerificationRepository
			.save(PasswordResetVerification.create(email, "hash-previous", NOW.plusMinutes(5), NOW));
		passwordResetVerificationRepository.flush();

		// 재발송 흐름: 이전 유효 행을 찾아 즉시 만료시키고 새 행을 저장한다.
		List<PasswordResetVerification> targets = passwordResetVerificationRepository
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(email, resendAt);
		assertThat(targets).extracting(PasswordResetVerification::getId).containsExactly(previous.getId());
		targets.forEach(target -> target.expire(resendAt));

		PasswordResetVerification renewed = passwordResetVerificationRepository
			.save(PasswordResetVerification.create(email, "hash-renewed", resendAt.plusMinutes(5), resendAt));
		passwordResetVerificationRepository.flush();

		List<PasswordResetVerification> remaining = passwordResetVerificationRepository
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(email, resendAt);

		assertThat(remaining).hasSize(1);
		assertThat(remaining.get(0).getId()).isEqualTo(renewed.getId());
	}
}
