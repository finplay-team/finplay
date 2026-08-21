// ASYNC 디스패치에서도 SecurityContext를 채우는지 검증하는 필터 단위 테스트 (이슈 #359)
package com.finplay.api.domain.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.DispatcherType;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

	private static final String PROTECTED_PATH = "/test/protected";
	private static final String VALID_TOKEN = "valid-access-token";
	private static final long USER_ID = 42L;
	private static final String ROLE = "USER";

	private JwtTokenProvider jwtTokenProvider;
	private JwtAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		jwtTokenProvider = mock(JwtTokenProvider.class);
		filter = new JwtAuthenticationFilter(jwtTokenProvider);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void doesNotSkipAsyncDispatch() {
		// OncePerRequestFilter 기본값(true)을 그대로 두면 SSE emitter의 completeWithError()가 유발하는
		// ASYNC 재디스패치에서 이 필터가 건너뛰어져 SecurityContext가 비고, AuthorizationFilter가 거부한다 (#359).
		assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();
	}

	@Test
	void populatesSecurityContextOnAsyncDispatchWithValidBearerToken() throws Exception {
		when(jwtTokenProvider.parseAccessToken(VALID_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, ROLE)));

		MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_PATH);
		request.setDispatcherType(DispatcherType.ASYNC);
		request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication).isNotNull();
		assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedUser(USER_ID, ROLE));
		assertThat(authentication.getAuthorities())
			.extracting(GrantedAuthority::getAuthority)
			.containsExactly("ROLE_" + ROLE);
		assertThat(chain.getRequest()).isEqualTo(request);
	}

	@Test
	void leavesSecurityContextEmptyOnAsyncDispatchWithoutBearerToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_PATH);
		request.setDispatcherType(DispatcherType.ASYNC);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(chain.getRequest()).isEqualTo(request);
	}
}
