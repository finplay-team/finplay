package com.finplay.api.domain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.global.filter.RequestIdFilter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RestAuthenticationEntryPointTest {

	private static final String REQUEST_ID = "request-id-172";

	@AfterEach
	void clearRequestId() {
		MDC.remove(RequestIdFilter.REQUEST_ID_MDC_KEY);
	}

	@Test
	void commenceWritesAndCommitsCompleteUnauthorizedJsonResponse() throws Exception {
		MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, REQUEST_ID);
		ObjectMapper objectMapper = new ObjectMapper();
		RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);
		MockHttpServletResponse response = new MockHttpServletResponse();

		entryPoint.commence(
			new MockHttpServletRequest(), response, mock(AuthenticationException.class));

		byte[] responseBody = response.getContentAsByteArray();
		JsonNode json = objectMapper.readTree(new String(responseBody, StandardCharsets.UTF_8));
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		assertThat(response.getContentLength()).isEqualTo(responseBody.length);
		assertThat(responseBody).isNotEmpty();
		assertThat(response.isCommitted()).isTrue();
		assertThat(json.path("error").path("code").asString()).isEqualTo("UNAUTHORIZED");
		assertThat(json.path("error").path("message").asString()).isEqualTo("인증이 필요합니다.");
		assertThat(json.path("error").path("requestId").asString()).isEqualTo(REQUEST_ID);
	}
}
