package com.finplay.api.domain.auth.config;

import com.finplay.api.domain.auth.token.JwtAuthenticationFilter;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
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
		"/api/auth/oauth/login-exchange",
		"/api/auth/oauth/reauth-exchange"
	};

	private static final String[] PUBLIC_GET_PATHS = {
		"/api/auth/oauth/*/callback",
		"/actuator/health",
		"/swagger-ui.html",
		"/swagger-ui/**",
		"/v3/api-docs/**"
	};

	private static final RequestMatcher OAUTH_LOGIN_AUTHORIZE_MATCHER = new AndRequestMatcher(
		PathPatternRequestMatcher.withDefaults()
			.matcher(HttpMethod.GET, "/api/auth/oauth/*/authorize"),
		request -> isLoginPurpose(request.getParameter("purpose")));

	private final JwtTokenProvider jwtTokenProvider;
	private final ObjectMapper objectMapper;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
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
