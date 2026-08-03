// 인증되지 않은 요청을 401 공통 오류 포맷으로 직접 직렬화해 응답하는 진입점
package com.finplay.api.auth.config;

import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.ErrorResponse;
import com.finplay.api.common.RequestIdFilter;
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
		// 필터체인에서 발생한 인증 실패는 GlobalExceptionHandler에 도달하지 않으므로 여기서 직접 쓴다.
		// 원인 예외 메시지는 노출하지 않고 고정 기본 메시지만 응답한다.
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
