// 인증번호 생성·만료 시각·시도 한도·발송 제한을 한곳에서 판정하는 정책 컴포넌트
package com.finplay.api.auth.verification;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.function.ToLongFunction;
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

	// 발송 제한 판정. 세 도메인의 차이는 집계 키뿐이라(이메일 단위 vs 회원 단위) 세는 함수만 인자로 받는다(#121 D5).
	// countCreatedAfter는 "주어진 시각 이후에 생성된 발송 행 수"를 돌려줘야 한다.
	//
	// 판정 순서(60초 → 1시간 → 하루)와 비교 연산자의 비대칭은 의도된 것이며 그대로 옮긴 것이다.
	// 첫 창만 > 0인 이유는 60초 창이 "한 건이라도 있으면 거부"하는 재발송 간격 규칙이기 때문이고,
	// 나머지 둘은 "N회째부터 거부"하는 횟수 한도라 >= LIMIT다. 셋을 같은 모양으로 "정리"하면
	// 재발송 간격이 사라지거나 1시간·하루 한도가 한 칸씩 밀려 429 경계가 달라진다.
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
