// 인증번호 생성·만료 시각·시도 한도를 한곳에서 판정하는 정책 컴포넌트
package com.finplay.api.auth.verification;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

// 가입 인증·이메일 변경·비밀번호 재설정 세 서비스가 공유하는 정책값이다(PRD AUTH-004·005·006).
// 값은 세 서비스에 복제돼 있던 것을 그대로 옮긴 것이며 하나도 바꾸지 않는다(#121 D5).
@Component
public class VerificationCodePolicy {

	public static final int CODE_TTL_MINUTES = 5;
	public static final int RESEND_INTERVAL_SECONDS = 60;
	public static final int HOURLY_LIMIT = 5;
	public static final int DAILY_LIMIT = 10;
	public static final int MAX_VERIFICATION_ATTEMPTS = 5;

	private static final int CODE_BOUND = 1_000_000; // 6자리(000000~999999) 난수 상한.
	private static final String CODE_FORMAT = "%06d";

	private final SecureRandom secureRandom = new SecureRandom();

	public String generateCode() {
		return String.format(CODE_FORMAT, secureRandom.nextInt(CODE_BOUND));
	}

	public LocalDateTime expiresAt(LocalDateTime now) {
		return now.plusMinutes(CODE_TTL_MINUTES);
	}

	// 증가 전 값 기준으로 판정한다 — 5회에 도달한 상태에서 다시 시도하면 증가시켜 6으로 만들고 429다(#116 D1).
	public boolean isAttemptLimitReached(int attemptCount) {
		return attemptCount >= MAX_VERIFICATION_ATTEMPTS;
	}
}
