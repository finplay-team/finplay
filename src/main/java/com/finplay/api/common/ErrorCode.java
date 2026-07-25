// PRD §5 공통 오류표의 코드·HTTP 상태·기본 메시지를 정의하는 열거형
package com.finplay.api.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	EMAIL_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "이메일 인증번호가 올바르지 않거나 만료되었습니다."),
	OAUTH_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "소셜 제공자가 이메일을 제공하지 않았습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
	DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
	EMAIL_VERIFICATION_REQUIRED(HttpStatus.CONFLICT, "이메일 인증이 필요합니다."),
	ACCOUNT_LINK_REQUIRED(HttpStatus.CONFLICT, "같은 이메일의 일반 회원이 있어 계정 연결이 필요합니다."),
	INSUFFICIENT_CASH(HttpStatus.CONFLICT, "현금 잔고가 부족합니다."),
	INSUFFICIENT_QTY(HttpStatus.CONFLICT, "매도 가능 수량이 부족합니다."),
	MARKET_CLOSED(HttpStatus.CONFLICT, "장이 종료되었습니다."),
	PRICE_UNAVAILABLE(HttpStatus.CONFLICT, "최신 시세를 조회할 수 없습니다."),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 키의 다른 요청이 이미 처리되었습니다."),
	UNSUPPORTED_ORDER_TYPE(HttpStatus.UNPROCESSABLE_CONTENT, "지원하지 않는 주문 유형입니다."),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus httpStatus;
	private final String defaultMessage;

	ErrorCode(HttpStatus httpStatus, String defaultMessage) {
		this.httpStatus = httpStatus;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getDefaultMessage() {
		return defaultMessage;
	}
}
