// 전역 예외 핸들러가 예외를 공통 오류 포맷·상태·requestId로 변환하는지 검증하는 @WebMvcTest 슬라이스 테스트
package com.finplay.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void serializesBusinessExceptionAsCommonFormatWithMappedStatus() throws Exception {
		mockMvc.perform(get("/test/insufficient-cash"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CASH"))
			.andExpect(jsonPath("$.error.message").value("현금 잔고가 부족합니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void mapsValidationFailureToValidationErrorCode() throws Exception {
		mockMvc.perform(post("/test/validate")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void mapsMalformedJsonBodyToValidationErrorCode() throws Exception {
		mockMvc.perform(post("/test/validate")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void mapsMissingRequiredRequestParamToValidationErrorCode() throws Exception {
		mockMvc.perform(get("/test/required-param"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void mapsConstraintViolationOnRequestParamToValidationErrorCode() throws Exception {
		mockMvc.perform(get("/test/min-param").param("page", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void mapsUnmappedPathToNotFoundInCommonFormat() throws Exception {
		mockMvc.perform(get("/test/no-such-endpoint"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void putsRequestIdInBothHeaderAndBodyWithSameValue() throws Exception {
		MvcResult result = mockMvc.perform(get("/test/insufficient-cash"))
			.andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
			.andReturn();

		String headerValue = result.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
		String bodyRequestId = JsonPath.read(
			result.getResponse().getContentAsString(), "$.error.requestId");

		assertThat(headerValue).isNotBlank();
		assertThat(bodyRequestId).isEqualTo(headerValue);
	}

	@Test
	void hidesInternalDetailsOnUnexpectedException() throws Exception {
		MvcResult result = mockMvc.perform(get("/test/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty())
			.andReturn();

		// 원본 예외 메시지(내부 정보)와 스택트레이스 흔적이 응답 본문에 새어나가지 않아야 한다.
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("top-secret-internal-detail");
		assertThat(body).doesNotContain("java.lang.IllegalStateException");
	}

	@Validated
	@RestController
	static class TestController {

		@org.springframework.web.bind.annotation.GetMapping("/test/insufficient-cash")
		void insufficientCash() {
			throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
		}

		@org.springframework.web.bind.annotation.GetMapping("/test/boom")
		void boom() {
			throw new IllegalStateException("top-secret-internal-detail");
		}

		@PostMapping("/test/validate")
		void validate(@Valid @RequestBody
		TestRequest request) {
			// 검증 통과 시 아무 것도 하지 않는다. 검증 실패 경로만 테스트한다.
		}

		@org.springframework.web.bind.annotation.GetMapping("/test/required-param")
		void requiredParam(@RequestParam
		String q) {
			// 필수 파라미터 누락 시 MissingServletRequestParameterException이 발생한다.
		}

		@org.springframework.web.bind.annotation.GetMapping("/test/min-param")
		void minParam(@RequestParam @Min(1)
		int page) {
			// @Min 위반 시 ConstraintViolationException이 발생한다.
		}
	}

	record TestRequest(@NotBlank(message = "이름은 필수입니다.")
	String name) {
	}
}
