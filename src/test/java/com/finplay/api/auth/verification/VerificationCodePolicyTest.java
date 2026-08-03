// 공통화된 인증번호 정책 상수·생성 형식·시도 한도 경계를 고정하는 단위 테스트 (#121 Task 3)
package com.finplay.api.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 공통화로 정책값이 한곳에 모이면서 상수 한 줄을 고치면 가입 인증·이메일 변경·비밀번호 재설정 세 도메인의 정책이
// 동시에 바뀐다. 그 사고를 여기서 잡는다 — 이 파일이 리팩터링의 핵심 방어선이다.
class VerificationCodePolicyTest {

	private final VerificationCodePolicy policy = new VerificationCodePolicy();

	@Test
	@DisplayName("정책 상수 5개는 PRD에 적힌 값에서 한 칸도 움직이지 않는다")
	void policyConstantsArePinnedToTheValuesWrittenInThePrd() {
		// 고정값 회귀 — PRD AUTH-004(249·251·255행)·AUTH-005(278행)·AUTH-006(297-298행)의 서술과 1:1로 대응한다.
		// 여기가 깨지면 정책을 바꾼 것이고, 세 도메인의 응답·제한이 함께 바뀐다. 기대값을 고쳐서 통과시키면 안 된다.
		assertThat(VerificationCodePolicy.CODE_TTL_MINUTES).as("인증번호 유효 시간(분)").isEqualTo(5);
		assertThat(VerificationCodePolicy.RESEND_INTERVAL_SECONDS).as("재발송 최소 간격(초)").isEqualTo(60);
		assertThat(VerificationCodePolicy.HOURLY_LIMIT).as("1시간 발송 한도(회)").isEqualTo(5);
		assertThat(VerificationCodePolicy.DAILY_LIMIT).as("하루 발송 한도(회)").isEqualTo(10);
		assertThat(VerificationCodePolicy.MAX_VERIFICATION_ATTEMPTS).as("인증번호 최대 시도(회)").isEqualTo(5);
	}

	@Test
	@DisplayName("expiresAt은 기준 시각에 만료 시간만 더하고 기준 시각 자체는 건드리지 않는다")
	void expiresAtAddsExactlyTheTtlToTheGivenInstant() {
		LocalDateTime now = LocalDateTime.of(2026, 8, 3, 10, 30, 0);

		LocalDateTime expiresAt = policy.expiresAt(now);

		assertThat(expiresAt).isEqualTo(LocalDateTime.of(2026, 8, 3, 10, 35, 0));
		assertThat(expiresAt).isEqualTo(now.plusMinutes(VerificationCodePolicy.CODE_TTL_MINUTES));
		assertThat(now).as("입력 시각은 불변이어야 한다").isEqualTo(LocalDateTime.of(2026, 8, 3, 10, 30, 0));
	}

	@ParameterizedTest(name = "attemptCount={0} → 한도 도달 {1}")
	@CsvSource({"0, false", "1, false", "4, false", "5, true", "6, true", "10, true"})
	@DisplayName("시도 한도 판정은 >= 경계다 — 5회에 도달한 상태에서 이미 한도다")
	void attemptLimitIsReachedOnceTheCountMeetsTheMaximum(int attemptCount, boolean reached) {
		// 이 경계가 >로 느슨해지면 6번째 요청이 429가 아니라 코드를 한 번 더 대조해 보게 되어 실질 한도가 6회가 된다.
		// 반대로 <=로 조여지면 5번째 오답이 429가 되어 기존 400 계약이 깨진다 (#116 D1).
		assertThat(policy.isAttemptLimitReached(attemptCount)).isEqualTo(reached);
	}

	@Test
	@DisplayName("generateCode는 항상 숫자 6자리이고 앞자리 0을 잘라내지 않는다")
	void generateCodeAlwaysProducesSixDigitsIncludingLeadingZeros() {
		List<String> codes = new ArrayList<>();
		for (int i = 0; i < 2_000; i++) {
			codes.add(policy.generateCode());
		}

		assertThat(codes).allSatisfy(code -> {
			assertThat(code).matches("\\d{6}");
			assertThat(Integer.parseInt(code)).isBetween(0, 999_999);
			// 앞자리 0이 잘리면 이 단정이 깨진다 — 100000 미만 값도 6자리로 채워져야 한다.
			assertThat(code).isEqualTo(String.format("%06d", Integer.parseInt(code)));
		});
		// 고정값을 돌려주는 구현으로 바뀌면(예: 상수 반환) 여기서 잡힌다.
		// 1,000,000 공간에서 2,000회를 뽑으므로 우연한 중복은 몇 건 생길 수 있다 — 개수 자체는 단정하지 않는다.
		assertThat(new HashSet<>(codes)).hasSizeGreaterThan(1_000);
	}

	@Test
	@DisplayName("100000 미만 인증번호도 실제로 발급되어 앞자리 0 채움 경로가 살아 있다")
	void codesBelowOneHundredThousandAreActuallyIssuedSoZeroPaddingIsExercised() {
		// 난수 상한이 1_000_000이면 한 번에 10% 확률이라 2000회 중 한 건도 안 나올 확률은 0.9^2000(≈10^-91)이다.
		// 상한을 900000 시작 같은 값으로 바꿔 앞자리 0이 사라지면 이 단정이 깨진다.
		boolean anyPaddedCode = false;
		for (int i = 0; i < 2_000 && !anyPaddedCode; i++) {
			anyPaddedCode = policy.generateCode().startsWith("0");
		}

		assertThat(anyPaddedCode).as("앞자리가 0인 인증번호가 한 건도 발급되지 않았다").isTrue();
	}
}
