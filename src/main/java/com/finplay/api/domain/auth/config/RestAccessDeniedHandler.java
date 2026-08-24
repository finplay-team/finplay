// 인증은 됐으나 권한이 없는 요청을 403 공통 오류 포맷으로 직접 직렬화해 응답하는 핸들러
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
		// 필터체인에서 발생한 인가 실패도 GlobalExceptionHandler에 도달하지 않으므로 여기서 직접 쓴다.
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
