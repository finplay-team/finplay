package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.EmailVerification;
import com.finplay.api.domain.auth.repository.EmailVerificationRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EmailVerificationTransactionIntegrationTest {

	private static final String SECRET = "test-email-verification-secret";
	private static final String CORRECT_CODE = "123456";
	private static final String INCORRECT_CODE = "000000";

	@Autowired
	private EmailVerificationService emailVerificationService;

	@Autowired
	private EmailVerificationRepository emailVerificationRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private Clock clock;

	@Test
	void mismatchedCodeCommitsAttemptCountAfterBadRequest() {
		String email = uniqueEmail();
		persistVerification(email);

		assertThatThrownBy(() -> emailVerificationService.confirmVerificationCode(email, INCORRECT_CODE))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);

		VerificationState persisted = loadStateInNewTransaction(email);
		assertThat(persisted.attemptCount()).isEqualTo(1);
	}

	@Test
	void sixthAttemptWithCorrectCodeCommitsRateLimitAndExpiration() {
		String email = uniqueEmail();
		persistVerification(email);

		for (int attempt = 1; attempt <= 5; attempt++) {
			assertThatThrownBy(() -> emailVerificationService.confirmVerificationCode(email, INCORRECT_CODE))
				.isInstanceOf(BusinessException.class)
				.extracting(ex -> ((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		}

		LocalDateTime beforeSixthAttempt = LocalDateTime.now(clock);
		assertThatThrownBy(() -> emailVerificationService.confirmVerificationCode(email, CORRECT_CODE))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
		LocalDateTime afterSixthAttempt = LocalDateTime.now(clock);

		VerificationState persisted = loadStateInNewTransaction(email);
		assertThat(persisted.attemptCount()).isEqualTo(6);
		assertThat(persisted.expiresAt())
			.isAfterOrEqualTo(beforeSixthAttempt)
			.isBeforeOrEqualTo(afterSixthAttempt);
	}

	private void persistVerification(String email) {
		LocalDateTime now = LocalDateTime.now(clock);
		emailVerificationRepository.saveAndFlush(EmailVerification.create(
			email, hmac(CORRECT_CODE), now.plusMinutes(5), now));
	}

	private VerificationState loadStateInNewTransaction(String email) {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return transactionTemplate.execute(status -> {
			EmailVerification verification = emailVerificationRepository
				.findFirstByEmailOrderByCreatedAtDesc(email)
				.orElseThrow();
			return new VerificationState(verification.getAttemptCount(), verification.getExpiresAt());
		});
	}

	private static String uniqueEmail() {
		return "verification-" + UUID.randomUUID() + "@finplay.com";
	}

	private static String hmac(String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record VerificationState(int attemptCount, LocalDateTime expiresAt) {
	}
}
