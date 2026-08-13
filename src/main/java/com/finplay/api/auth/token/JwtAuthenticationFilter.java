// Authorization Bearer 헤더의 Access Token을 파싱해 SecurityContext에 인증 주체를 채우는 필터
package com.finplay.api.auth.token;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

	private final JwtTokenProvider jwtTokenProvider;

	public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
		this.jwtTokenProvider = jwtTokenProvider;
	}

	// SSE emitter가 completeWithError()로 완료되면 서블릿 컨테이너가 같은 요청에 ASYNC 재디스패치를 일으키는데,
	// 이 필터가 기본값(true)대로 그 디스패치를 건너뛰면 SecurityContext가 비어 AuthorizationFilter가 거부한다
	// (이슈 #359). STATELESS라 매 디스패치마다 Authorization 헤더에서 다시 인증해도 비용이 작다.
	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		// 토큰이 없거나 잘못되었어도 여기서 응답을 만들지 않는다. 인증을 비운 채 통과시키면
		// 보호 경로는 AuthorizationFilter가 거부하고, 공개 경로는 정상 처리된다.
		resolveBearerToken(request)
			.flatMap(jwtTokenProvider::parseAccessToken)
			.ifPresent(this::authenticate);
		filterChain.doFilter(request, response);
	}

	private Optional<String> resolveBearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return Optional.empty();
		}
		return Optional.of(header.substring(BEARER_PREFIX.length()));
	}

	private void authenticate(AuthenticatedUser authenticatedUser) {
		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
			authenticatedUser,
			null,
			List.of(new SimpleGrantedAuthority(ROLE_AUTHORITY_PREFIX + authenticatedUser.role())));
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
