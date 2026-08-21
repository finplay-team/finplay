// EmailChangeVerification의 시도 횟수 증가·소비 처리 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class EmailChangeVerificationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 10, 30, 0);

	@Test
	void incrementAttemptCountIncreasesCountFromZeroAndReturnsNewValue() {
		EmailChangeVerification verification = newVerification();

		int firstResult = verification.incrementAttemptCount();
		int secondResult = verification.incrementAttemptCount();

		assertThat(firstResult).isEqualTo(1);
		assertThat(secondResult).isEqualTo(2);
		assertThat(verification.getAttemptCount()).isEqualTo(2);
	}

	@Test
	void consumeSetsConsumedAtToGivenTime() {
		EmailChangeVerification verification = newVerification();

		verification.consume(NOW.plusMinutes(1));

		assertThat(verification.getConsumedAt()).isEqualTo(NOW.plusMinutes(1));
	}

	private static EmailChangeVerification newVerification() {
		User user = User.create("verify@finplay.com", "password-hash", "verify-user", NOW);
		return EmailChangeVerification.create(user, "new@finplay.com", "code-hash", NOW.plusMinutes(5), NOW);
	}
}
