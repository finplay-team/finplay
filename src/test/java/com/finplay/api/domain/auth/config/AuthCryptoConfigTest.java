package com.finplay.api.domain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(AuthCryptoConfig.class)
class AuthCryptoConfigTest {

	private final PasswordEncoder passwordEncoder;

	@Autowired
	AuthCryptoConfigTest(PasswordEncoder passwordEncoder) {
		this.passwordEncoder = passwordEncoder;
	}

	@ParameterizedTest
	@MethodSource("passwordsAllowedBySignupContract")
	void passwordEncoderSupportsPasswordsAllowedBySignupContract(String password) {
		String encodedPassword = passwordEncoder.encode(password);

		assertThat(encodedPassword).isNotEqualTo(password);
		assertThat(passwordEncoder.matches(password, encodedPassword)).isTrue();
	}

	private static Stream<String> passwordsAllowedBySignupContract() {
		return Stream.of("a".repeat(100), "가".repeat(25));
	}
}
