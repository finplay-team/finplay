package com.finplay.api.domain.auth.verification;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Component;

@Component
public class VerificationCodePolicy {

	public static final int CODE_TTL_MINUTES = 5;
	public static final int RESEND_INTERVAL_SECONDS = 60;
	public static final int HOURLY_LIMIT = 5;
	public static final int DAILY_LIMIT = 10;
	public static final int MAX_VERIFICATION_ATTEMPTS = 5;

	private static final int CODE_BOUND = 1_000_000;
	private static final String CODE_FORMAT = "%06d";

	private final SecureRandom secureRandom = new SecureRandom();

	public String generateCode() {
		return String.format(CODE_FORMAT, secureRandom.nextInt(CODE_BOUND));
	}

	public LocalDateTime expiresAt(LocalDateTime now) {
		return now.plusMinutes(CODE_TTL_MINUTES);
	}

	public boolean isAttemptLimitReached(int attemptCount) {
		return attemptCount >= MAX_VERIFICATION_ATTEMPTS;
	}

	public void checkSendRateLimit(LocalDateTime now, ToLongFunction<LocalDateTime> countCreatedAfter) {
		if (countCreatedAfter.applyAsLong(now.minusSeconds(RESEND_INTERVAL_SECONDS)) > 0) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (countCreatedAfter.applyAsLong(now.minusHours(1)) >= HOURLY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (countCreatedAfter.applyAsLong(now.minusDays(1)) >= DAILY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
	}
}
