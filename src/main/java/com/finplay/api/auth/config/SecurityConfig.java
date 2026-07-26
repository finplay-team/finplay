// 공개 경로 화이트리스트와 Bearer 인증 필터, 401·403 공통 응답을 묶는 Spring Security 설정
package com.finplay.api.auth.config;

import com.finplay.api.auth.token.JwtAuthenticationFilter;
import com.finplay.api.auth.token.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private static final String[] PUBLIC_POST_PATHS = {
		"/api/auth/signup",
		"/api/auth/login",
		"/api/auth/refresh",
		"/api/auth/email-verifications",
		"/api/auth/email-verifications/confirm"
	};

	private static final String[] PUBLIC_GET_PATHS = {
		"/api/auth/oauth/*/authorize",
		"/api/auth/oauth/*/callback",
		"/actuator/health",
		"/swagger-ui.html",
		"/swagger-ui/**",
		"/v3/api-docs/**"
	};

	private final JwtTokenProvider jwtTokenProvider;
	private final ObjectMapper objectMapper;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		// 필터는 빈으로 등록하지 않는다. Filter 빈은 서블릿 컨테이너에 자동 등록되어 Security 체인 밖에서도 실행된다.
		http
			.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
				.requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(
				new JwtAuthenticationFilter(jwtTokenProvider),
				UsernamePasswordAuthenticationFilter.class)
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper))
				.accessDeniedHandler(new RestAccessDeniedHandler(objectMapper)));
		return http.build();
	}
}
