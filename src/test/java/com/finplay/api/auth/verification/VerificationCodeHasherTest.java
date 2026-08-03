// 공통화된 인증번호 해셔의 알고리즘·인코딩·시크릿 분리를 고정하는 단위 테스트 (#121 Task 3)
package com.finplay.api.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 해시 방식이 바뀌면 DB에 저장된 기존 code_hash가 전부 대조 불가가 되어 발송된 인증번호가 통째로 무효가 된다.
// 그래서 "지금 구현이 무엇을 내놓는가"가 아니라 외부에서 독립적으로 계산한 고정값과 대조한다.
class VerificationCodeHasherTest {

	private static final String SECRET = "fixed-regression-secret";
	private static final String OTHER_SECRET = "fixed-regression-secret-2";
	private static final String CODE = "123456";
	// HMAC-SHA-256(key = SECRET(UTF-8), message = CODE(UTF-8))의 소문자 hex.
	// 이 값은 Java 구현이 아니라 표준 HMAC 정의로 별도 계산한 것이라, 구현이 바뀌면 여기서 곧바로 어긋난다.
	private static final String EXPECTED_HASH = "0526342cedbb298c49520fceb8d08d802058736a9425cd858b2299bd7a0e2aaa";
	private static final String EXPECTED_HASH_OF_ALL_ZERO_CODE = "67bdc3f3a50b6f093beaff852d090b427f50b4541899c97b4472e5ce99236566";

	private final VerificationCodeHasher hasher = new VerificationCodeHasher(SECRET);

	@Test
	@DisplayName("HMAC-SHA-256 hex 결과가 고정값과 바이트 단위로 같다 — 알고리즘·인코딩 회귀 방지")
	void hashMatchesTheIndependentlyComputedFixedValue() {
		assertThat(hasher.hmac(CODE)).isEqualTo(EXPECTED_HASH);
		// 앞자리 0으로 시작하는 인증번호도 문자열 그대로 해싱한다(숫자로 파싱해 0을 잃지 않는다).
		assertThat(hasher.hmac("000000")).isEqualTo(EXPECTED_HASH_OF_ALL_ZERO_CODE);
	}

	@Test
	@DisplayName("결과는 항상 소문자 hex 64자다")
	void hashIsAlwaysSixtyFourLowercaseHexCharacters() {
		assertThat(hasher.hmac(CODE)).hasSize(64).matches("[0-9a-f]{64}");
		assertThat(hasher.hmac("000000")).hasSize(64).matches("[0-9a-f]{64}");
		assertThat(hasher.hmac("999999")).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	@DisplayName("같은 코드·같은 시크릿이면 몇 번을 계산해도 같은 해시다")
	void sameCodeAndSecretAlwaysProduceTheSameHash() {
		assertThat(hasher.hmac(CODE))
			.isEqualTo(hasher.hmac(CODE))
			.isEqualTo(new VerificationCodeHasher(SECRET).hmac(CODE));
	}

	@Test
	@DisplayName("같은 코드라도 시크릿이 다르면 해시가 다르다 — 용도별 시크릿 분리의 근거")
	void differentSecretsProduceDifferentHashesForTheSameCode() {
		// 이 성질이 깨지면 한쪽 경로의 인증번호가 다른 경로에서도 통과한다 (#121 D8).
		assertThat(new VerificationCodeHasher(OTHER_SECRET).hmac(CODE)).isNotEqualTo(EXPECTED_HASH);
	}

	@Test
	@DisplayName("같은 시크릿이라도 코드가 다르면 해시가 다르다")
	void differentCodesProduceDifferentHashesUnderTheSameSecret() {
		assertThat(hasher.hmac("123456")).isNotEqualTo(hasher.hmac("123457"));
	}
}
