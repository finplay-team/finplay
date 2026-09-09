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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException accessDeniedException) throws IOException {
		ErrorResponse body = ErrorResponse.of(
			ErrorCode.FORBIDDEN,
			ErrorCode.FORBIDDEN.getDefaultMessage(),
			MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY));
		byte[] responseBody = objectMapper.writeValueAsBytes(body);
		response.setStatus(ErrorCode.FORBIDDEN.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setContentLength(responseBody.length);
		response.getOutputStream().write(responseBody);
		response.flushBuffer();
	}
}
