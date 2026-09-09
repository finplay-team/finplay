package com.finplay.api.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordResetVerificationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 10, 30, 0);

	@Test
	@DisplayName("create는 해시·만료·발송시각을 채우고 시도 횟수 0·미소비 상태로 시작한다")
	void createStoresHashExpiryAndSentTimeWithZeroAttemptCount() {
		PasswordResetVerification verification = PasswordResetVerification
			.create("reset@finplay.com", "code-hash-value", NOW.plusMinutes(5), NOW);

		assertThat(verification.getEmail()).isEqualTo("reset@finplay.com");
		assertThat(verification.getCodeHash()).isEqualTo("code-hash-value");
		assertThat(verification.getAttemptCount()).isZero();
		assertThat(verification.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(verification.getLastSentAt()).isEqualTo(NOW);
		assertThat(verification.getCreatedAt()).isEqualTo(NOW);
		assertThat(verification.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("createRejected는 발송하지 않은 행이므로 코드·만료·발송시각이 모두 없고 생성 시각만 남는다")
	void createRejectedLeavesCodeExpiryAndSentTimeNull() {
		PasswordResetVerification rejected = PasswordResetVerification.createRejected("unknown@finplay.com", NOW);

		assertThat(rejected.getEmail()).isEqualTo("unknown@finplay.com");
		assertThat(rejected.getCodeHash()).isNull();
		assertThat(rejected.getExpiresAt()).isNull();
		assertThat(rejected.getLastSentAt()).isNull();
		assertThat(rejected.getConsumedAt()).isNull();
		assertThat(rejected.getAttemptCount()).isZero();
		assertThat(rejected.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("expire는 만료 시각을 현재 시각으로 당겨 유효 구간에서 즉시 제외시킨다")
	void expireMovesExpiresAtToGivenTimeSoRowIsNoLongerAfterNow() {
		PasswordResetVerification verification = PasswordResetVerification
			.create("resend@finplay.com", "code-hash-value", NOW.plusMinutes(5), NOW);

		verification.expire(NOW.plusSeconds(70));

		assertThat(verification.getExpiresAt()).isEqualTo(NOW.plusSeconds(70));
		assertThat(verification.getExpiresAt()).isBeforeOrEqualTo(NOW.plusSeconds(70));
	}

	@Test
	@DisplayName("expire는 코드 해시와 소비 여부를 건드리지 않는다")
	void expireKeepsCodeHashAndConsumedAtUntouched() {
		PasswordResetVerification verification = PasswordResetVerification
			.create("keep@finplay.com", "code-hash-value", NOW.plusMinutes(5), NOW);

		verification.expire(NOW.plusSeconds(70));

		assertThat(verification.getCodeHash()).isEqualTo("code-hash-value");
		assertThat(verification.getConsumedAt()).isNull();
		assertThat(verification.getLastSentAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("incrementAttemptCount는 0에서 1씩 올리며 증가 후의 값을 반환한다")
	void incrementAttemptCountIncreasesCountFromZeroAndReturnsNewValue() {
		PasswordResetVerification verification = newSentVerification();

		int firstResult = verification.incrementAttemptCount();
		int secondResult = verification.incrementAttemptCount();

		assertThat(firstResult).isEqualTo(1);
		assertThat(secondResult).isEqualTo(2);
		assertThat(verification.getAttemptCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("incrementAttemptCount는 시도 횟수 외의 필드를 건드리지 않는다")
	void incrementAttemptCountKeepsOtherFieldsUntouched() {
		PasswordResetVerification verification = newSentVerification();

		verification.incrementAttemptCount();

		assertThat(verification.getEmail()).isEqualTo("attempt@finplay.com");
		assertThat(verification.getCodeHash()).isEqualTo("code-hash-value");
		assertThat(verification.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(verification.getLastSentAt()).isEqualTo(NOW);
		assertThat(verification.getCreatedAt()).isEqualTo(NOW);
		assertThat(verification.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("consume은 소비 시각을 주어진 시각으로 기록한다")
	void consumeSetsConsumedAtToGivenTime() {
		PasswordResetVerification verification = newSentVerification();

		verification.consume(NOW.plusMinutes(1));

		assertThat(verification.getConsumedAt()).isEqualTo(NOW.plusMinutes(1));
	}

	@Test
	@DisplayName("consume은 소비 시각 외의 필드를 건드리지 않는다 — 만료를 앞당기거나 시도 횟수를 올리지 않는다")
	void consumeKeepsOtherFieldsUntouched() {
		PasswordResetVerification verification = newSentVerification();
		verification.incrementAttemptCount();

		verification.consume(NOW.plusMinutes(1));

		assertThat(verification.getEmail()).isEqualTo("attempt@finplay.com");
		assertThat(verification.getCodeHash()).isEqualTo("code-hash-value");
		assertThat(verification.getAttemptCount()).isEqualTo(1);
		assertThat(verification.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(verification.getLastSentAt()).isEqualTo(NOW);
		assertThat(verification.getCreatedAt()).isEqualTo(NOW);
	}

	private static PasswordResetVerification newSentVerification() {
		return PasswordResetVerification.create("attempt@finplay.com", "code-hash-value", NOW.plusMinutes(5), NOW);
	}
}
