package com.finplay.api.domain.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VerificationCodeHasherTest {

	private static final String SECRET = "fixed-regression-secret";
	private static final String OTHER_SECRET = "fixed-regression-secret-2";
	private static final String CODE = "123456";
	private static final String EXPECTED_HASH = "0526342cedbb298c49520fceb8d08d802058736a9425cd858b2299bd7a0e2aaa";
	private static final String EXPECTED_HASH_OF_ALL_ZERO_CODE = "67bdc3f3a50b6f093beaff852d090b427f50b4541899c97b4472e5ce99236566";

	private final VerificationCodeHasher hasher = new VerificationCodeHasher(SECRET);

	@Test
	@DisplayName("HMAC-SHA-256 hex 결과가 고정값과 바이트 단위로 같다 — 알고리즘·인코딩 회귀 방지")
	void hashMatchesTheIndependentlyComputedFixedValue() {
		assertThat(hasher.hmac(CODE)).isEqualTo(EXPECTED_HASH);
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
		assertThat(new VerificationCodeHasher(OTHER_SECRET).hmac(CODE)).isNotEqualTo(EXPECTED_HASH);
	}

	@Test
	@DisplayName("같은 시크릿이라도 코드가 다르면 해시가 다르다")
	void differentCodesProduceDifferentHashesUnderTheSameSecret() {
		assertThat(hasher.hmac("123456")).isNotEqualTo(hasher.hmac("123457"));
	}
}
