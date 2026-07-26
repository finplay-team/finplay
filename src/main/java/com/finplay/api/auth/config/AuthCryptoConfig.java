// 회원 비밀번호 BCrypt 해시에 사용할 PasswordEncoder 빈을 제공하는 인증 설정
package com.finplay.api.auth.config;

import com.finplay.api.auth.crypto.Sha256BcryptPasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AuthCryptoConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new Sha256BcryptPasswordEncoder();
	}
}
