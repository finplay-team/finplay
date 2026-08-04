// 인가 실패 핸들러가 403 공통 오류를 완결된 바이트 응답으로 기록하는지 검증하는 단위 테스트다.
package com.finplay.api.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.common.RequestIdFilter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RestAccessDeniedHandlerTest {

	private static final String REQUEST_ID = "request-id-173";

	@AfterEach
	void clearRequestId() {
		MDC.remove(RequestIdFilter.REQUEST_ID_MDC_KEY);
	}

	@Test
	void handleWritesAndCommitsCompleteForbiddenJsonResponse() throws Exception {
		MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, REQUEST_ID);
		ObjectMapper objectMapper = new ObjectMapper();
		RestAccessDeniedHandler handler = new RestAccessDeniedHandler(objectMapper);
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.handle(
			new MockHttpServletRequest(), response, mock(AccessDeniedException.class));

		byte[] responseBody = response.getContentAsByteArray();
		JsonNode json = objectMapper.readTree(new String(responseBody, StandardCharsets.UTF_8));
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		assertThat(response.getContentLength()).isEqualTo(responseBody.length);
		assertThat(responseBody).isNotEmpty();
		assertThat(response.isCommitted()).isTrue();
		assertThat(json.path("error").path("code").asString()).isEqualTo("FORBIDDEN");
		assertThat(json.path("error").path("message").asString()).isEqualTo("접근 권한이 없습니다.");
		assertThat(json.path("error").path("requestId").asString()).isEqualTo(REQUEST_ID);
	}
}
