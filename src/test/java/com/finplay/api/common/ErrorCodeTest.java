// ErrorCode enum의 코드 ↔ HTTP 상태 매핑이 PRD §5 공통 오류표와 일치하는지 검증하는 단위 테스트
package com.finplay.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

	@Test
	void hasExactlySixteenErrorCodesFromPrd() {
		assertThat(ErrorCode.values()).hasSize(16);
	}

	@Test
	void mapsEveryErrorCodeToPrdHttpStatus() {
		Map<ErrorCode, HttpStatus> expected = Map.ofEntries(
			Map.entry(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.EMAIL_VERIFICATION_FAILED, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.OAUTH_EMAIL_REQUIRED, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
			Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
			Map.entry(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.DUPLICATE_RESOURCE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.EMAIL_VERIFICATION_REQUIRED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.ACCOUNT_LINK_REQUIRED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.INSUFFICIENT_CASH, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.INSUFFICIENT_QTY, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MARKET_CLOSED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRICE_UNAVAILABLE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.UNSUPPORTED_ORDER_TYPE, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(ErrorCode.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS));

		// 표에 정의된 16개 코드를 하나도 빠짐없이 순회하며 상태를 대조한다.
		assertThat(expected).hasSize(ErrorCode.values().length);
		for (ErrorCode code : ErrorCode.values()) {
			assertThat(code.getHttpStatus())
				.as("%s 의 HTTP 상태", code.name())
				.isEqualTo(expected.get(code));
		}
	}

	@Test
	void keepsNumericStatusForRepresentativeCodes() {
		assertThat(ErrorCode.VALIDATION_ERROR.getHttpStatus().value()).isEqualTo(400);
		assertThat(ErrorCode.UNAUTHORIZED.getHttpStatus().value()).isEqualTo(401);
		assertThat(ErrorCode.FORBIDDEN.getHttpStatus().value()).isEqualTo(403);
		assertThat(ErrorCode.NOT_FOUND.getHttpStatus().value()).isEqualTo(404);
		assertThat(ErrorCode.INSUFFICIENT_CASH.getHttpStatus().value()).isEqualTo(409);
		assertThat(ErrorCode.UNSUPPORTED_ORDER_TYPE.getHttpStatus().value()).isEqualTo(422);
		assertThat(ErrorCode.TOO_MANY_REQUESTS.getHttpStatus().value()).isEqualTo(429);
	}

	@Test
	void exposesNonBlankDefaultMessageForEveryCode() {
		for (ErrorCode code : ErrorCode.values()) {
			assertThat(code.getDefaultMessage())
				.as("%s 의 기본 메시지", code.name())
				.isNotBlank();
		}
	}
}
