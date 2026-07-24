// 요청마다 UUID requestId를 생성해 MDC·응답 헤더에 싣는 서블릿 필터
package com.finplay.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_MDC_KEY = "requestId";
	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	@Override
	protected void doFilterInternal(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		MDC.put(REQUEST_ID_MDC_KEY, requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(REQUEST_ID_MDC_KEY);
		}
	}
}
