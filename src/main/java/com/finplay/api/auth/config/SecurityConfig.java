// 공개 경로 화이트리스트와 Bearer 인증 필터, 401·403 공통 응답을 묶는 Spring Security 설정
package com.finplay.api.auth.config;

import com.finplay.api.auth.token.JwtAuthenticationFilter;
import com.finplay.api.auth.token.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
		"/api/auth/email-verifications/confirm",
		"/api/auth/password-resets",
		"/api/auth/password-resets/confirm",
		// OAuth 로그인 callback이 302로 넘긴 1회용 교환 코드를 토큰으로 바꾼다 — 아직 로그인 전이라 인증이 없다.
		"/api/auth/oauth/login-exchange"
	};

	private static final String[] PUBLIC_GET_PATHS = {
		"/api/auth/oauth/*/callback",
		"/actuator/health",
		"/swagger-ui.html",
		"/swagger-ui/**",
		"/v3/api-docs/**"
	};

	// purpose가 없거나 login인 authorize만 공개다. 그 밖의 값(reauth 포함)은 anyRequest로 떨어져 인증을 요구한다.
	private static final RequestMatcher OAUTH_LOGIN_AUTHORIZE_MATCHER = new AndRequestMatcher(
		PathPatternRequestMatcher.withDefaults()
			.matcher(HttpMethod.GET, "/api/auth/oauth/*/authorize"),
		request -> isLoginPurpose(request.getParameter("purpose")));

	private final JwtTokenProvider jwtTokenProvider;
	private final ObjectMapper objectMapper;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		// 필터는 빈으로 등록하지 않는다. Filter 빈은 서블릿 컨테이너에 자동 등록되어 Security 체인 밖에서도 실행된다.
		http
			// CorsConfig의 CorsConfigurationSource 빈을 쓴다. 이 설정이 CorsFilter를 체인 맨 앞에 놓으므로
			// preflight(OPTIONS)는 JwtAuthenticationFilter와 인가 규칙에 닿기 전에 응답된다 (ADR-0022).
			.cors(Customizer.withDefaults())
			.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
				.requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
				.requestMatchers(OAUTH_LOGIN_AUTHORIZE_MATCHER).permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(
				new JwtAuthenticationFilter(jwtTokenProvider),
				UsernamePasswordAuthenticationFilter.class)
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper))
				.accessDeniedHandler(new RestAccessDeniedHandler(objectMapper)));
		return http.build();
	}

	private static boolean isLoginPurpose(String purpose) {
		return purpose == null || purpose.isBlank() || "login".equalsIgnoreCase(purpose);
	}
}
