// ErrorCode enum의 코드 ↔ HTTP 상태 매핑이 PRD §5 공통 오류표와 일치하는지 검증하는 단위 테스트
package com.finplay.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

	@Test
	void declaresEveryErrorCodeFromPrdAndOAuthSpecWithoutUnlistedOnes() {
		assertThat(ErrorCode.values()).hasSize(37);
	}

	@Test
	void mapsEveryErrorCodeToPrdHttpStatus() {
		Map<ErrorCode, HttpStatus> expected = Map.ofEntries(
			Map.entry(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.EMAIL_VERIFICATION_FAILED, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.OAUTH_AUTHORIZATION_FAILED, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.OAUTH_EMAIL_REQUIRED, HttpStatus.BAD_REQUEST),
			Map.entry(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
			Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
			Map.entry(ErrorCode.REAUTHENTICATION_FAILED, HttpStatus.FORBIDDEN),
			Map.entry(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED),
			Map.entry(ErrorCode.FAVORITE_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.WATCHLIST_ITEM_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(ErrorCode.DUPLICATE_RESOURCE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_STEP_LOCKED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_ALREADY_COMPLETED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_EVIDENCE_MISSING, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.INSTRUMENT_NOT_TRADABLE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.EMAIL_VERIFICATION_REQUIRED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.ACCOUNT_LINK_REQUIRED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.SOCIAL_ACCOUNT_ONLY, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.INSUFFICIENT_CASH, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.INSUFFICIENT_QTY, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.MARKET_CLOSED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRICE_UNAVAILABLE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_PRICE_SESSION_ALREADY_ACTIVE, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_PRICE_TICK_CONFLICT, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_LIMIT_ORDER_ALREADY_PENDING, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.PRACTICE_PRICE_SESSION_MISMATCH, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.ORDER_ALREADY_FILLED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.ORDER_ALREADY_CANCELLED, HttpStatus.CONFLICT),
			Map.entry(ErrorCode.UNSUPPORTED_ORDER_TYPE, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(ErrorCode.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS),
			Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
			Map.entry(ErrorCode.OAUTH_PROVIDER_ERROR, HttpStatus.BAD_GATEWAY),
			Map.entry(ErrorCode.MARKET_DATA_PROVIDER_ERROR, HttpStatus.BAD_GATEWAY));

		// PRD와 OAuth spec에 정의된 코드를 하나도 빠짐없이 순회하며 상태를 대조한다.
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
		assertThat(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus().value()).isEqualTo(405);
		assertThat(ErrorCode.INSUFFICIENT_CASH.getHttpStatus().value()).isEqualTo(409);
		assertThat(ErrorCode.UNSUPPORTED_ORDER_TYPE.getHttpStatus().value()).isEqualTo(422);
		assertThat(ErrorCode.TOO_MANY_REQUESTS.getHttpStatus().value()).isEqualTo(429);
		assertThat(ErrorCode.INTERNAL_ERROR.getHttpStatus().value()).isEqualTo(500);
		assertThat(ErrorCode.OAUTH_PROVIDER_ERROR.getHttpStatus().value()).isEqualTo(502);
	}

	@Test
	void favoriteNotFoundKeepsPublicDefaultMessage() {
		assertThat(ErrorCode.FAVORITE_NOT_FOUND.getDefaultMessage())
			.isEqualTo("즐겨찾기를 찾을 수 없습니다.");
	}

	@Test
	void practiceErrorsKeepPublicDefaultMessages() {
		assertThat(ErrorCode.PRACTICE_STEP_LOCKED.getDefaultMessage())
			.isEqualTo("선행 실습 단계를 완료해야 합니다.");
		assertThat(ErrorCode.PRACTICE_ALREADY_COMPLETED.getDefaultMessage())
			.isEqualTo("이미 완료한 실습입니다.");
		assertThat(ErrorCode.PRACTICE_EVIDENCE_MISSING.getDefaultMessage())
			.isEqualTo("실습 진행에 필요한 증거를 확인할 수 없습니다.");
		assertThat(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED.getDefaultMessage())
			.isEqualTo("실습 매수 후 5분이 지나 이 시도는 만료됐습니다. 다시 매수해 주세요.");
		assertThat(ErrorCode.PRACTICE_PRICE_SESSION_ALREADY_ACTIVE.getDefaultMessage())
			.isEqualTo("이미 진행 중인 가상 가격 세션이 있습니다.");
		assertThat(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED.getDefaultMessage())
			.isEqualTo("이미 종료된 가상 가격 세션입니다.");
		assertThat(ErrorCode.PRACTICE_PRICE_TICK_CONFLICT.getDefaultMessage())
			.isEqualTo("요청한 tick이 현재 진행 위치와 일치하지 않습니다.");
		assertThat(ErrorCode.PRACTICE_LIMIT_ORDER_ALREADY_PENDING.getDefaultMessage())
			.isEqualTo("이미 대기 중인 교육 지정가 주문이 있습니다.");
		assertThat(ErrorCode.PRACTICE_PRICE_SESSION_MISMATCH.getDefaultMessage())
			.isEqualTo("주문의 사용자 또는 종목이 가상 가격 세션과 일치하지 않습니다.");
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
