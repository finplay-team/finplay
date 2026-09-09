package com.finplay.api.domain.auth.config;

import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.global.exception.ErrorResponse;
import com.finplay.api.global.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException) throws IOException {
		ErrorResponse body = ErrorResponse.of(
			ErrorCode.UNAUTHORIZED,
			ErrorCode.UNAUTHORIZED.getDefaultMessage(),
			MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY));
		byte[] responseBody = objectMapper.writeValueAsBytes(body);
		response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setContentLength(responseBody.length);
		response.getOutputStream().write(responseBody);
		response.flushBuffer();
	}
}
