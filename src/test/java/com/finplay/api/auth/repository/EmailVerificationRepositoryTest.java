// email_verifications의 UNIQUE(token_hash)·기간 발송 집계·미확인 행 조회 쿼리를 검증하는 슬라이스 테스트 (ADR-0003)
package com.finplay.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.EmailVerification;
import java.time.LocalDateTime;
import java.util.List;
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
class EmailVerificationRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 25, 10, 30, 0);
	private static final String EMAIL = "user@finplay.com";

	@Autowired
	private EmailVerificationRepository emailVerificationRepository;

	@Test
	@DisplayName("countByEmailAndCreatedAtAfter는 같은 이메일이면서 기준 시각 이후에 생성된 행만 센다")
	void countByEmailAndCreatedAtAfterCountsOnlyMatchingRows() {
		// 기준 시각(NOW-15분) 이전 1건, 이후 2건 + 다른 이메일 1건.
		emailVerificationRepository.save(newVerification(EMAIL, NOW.minusMinutes(30)));
		emailVerificationRepository.save(newVerification(EMAIL, NOW.minusMinutes(10)));
		emailVerificationRepository.save(newVerification(EMAIL, NOW.minusSeconds(30)));
		emailVerificationRepository.save(newVerification("other@finplay.com", NOW));
		emailVerificationRepository.flush();

		long count = emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusMinutes(15));

		assertThat(count).isEqualTo(2);
	}

	@Test
	@DisplayName("token_hash가 같으면 UNIQUE(token_hash) 제약으로 저장이 거부된다")
	void duplicateTokenHashViolatesUniqueConstraint() {
		EmailVerification first = newVerification(EMAIL, NOW);
		ReflectionTestUtils.setField(first, "tokenHash", "same-token-hash");
		emailVerificationRepository.saveAndFlush(first);

		EmailVerification second = newVerification("another@finplay.com", NOW);
		ReflectionTestUtils.setField(second, "tokenHash", "same-token-hash");

		assertThatThrownBy(() -> emailVerificationRepository.saveAndFlush(second))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("token_hash가 NULL인 미확인 행은 여러 건 저장돼도 UNIQUE 제약에 걸리지 않는다")
	void multipleNullTokenHashRowsAreAllowed() {
		emailVerificationRepository.saveAndFlush(newVerification(EMAIL, NOW));

		assertThat(emailVerificationRepository.count()).isEqualTo(1);
		// 두 번째 미확인 행(token_hash NULL)도 정상 저장된다 — MySQL은 UNIQUE 컬럼의 NULL 중복을 허용.
		emailVerificationRepository.saveAndFlush(newVerification(EMAIL, NOW.plusSeconds(1)));

		assertThat(emailVerificationRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("미확인 행 조회는 verified_at이 NULL이고 expires_at이 기준 시각 이후인 같은 이메일 행만 반환한다")
	void findUnverifiedRowsFiltersByVerifiedAtAndExpiresAt() {
		// 매칭: 미확인 + 유효.
		EmailVerification matching = EmailVerification.create(EMAIL, "hash", NOW.plusMinutes(10), NOW);
		emailVerificationRepository.save(matching);

		// 제외: 만료됨(expires_at이 NOW 이전).
		emailVerificationRepository.save(EmailVerification.create(EMAIL, "hash", NOW.minusMinutes(1), NOW));

		// 제외: 이미 확인됨(verified_at 존재).
		EmailVerification verified = EmailVerification.create(EMAIL, "hash", NOW.plusMinutes(10), NOW);
		ReflectionTestUtils.setField(verified, "verifiedAt", NOW.minusMinutes(1));
		emailVerificationRepository.save(verified);

		// 제외: 다른 이메일.
		emailVerificationRepository
			.save(EmailVerification.create("other@finplay.com", "hash", NOW.plusMinutes(10), NOW));
		emailVerificationRepository.flush();

		List<EmailVerification> found = emailVerificationRepository
			.findByEmailAndVerifiedAtIsNullAndExpiresAtAfter(EMAIL, NOW);

		assertThat(found).hasSize(1);
		assertThat(found.get(0).getExpiresAt()).isEqualTo(NOW.plusMinutes(10));
		assertThat(found.get(0).getVerifiedAt()).isNull();
	}

	private static EmailVerification newVerification(String email, LocalDateTime createdAt) {
		// createdAt = now 파라미터 (엔티티가 생성 시각을 팩토리 인자로 받음). expires_at은 집계 테스트에 무관.
		return EmailVerification.create(email, "code-hash", createdAt.plusMinutes(5), createdAt);
	}
}
