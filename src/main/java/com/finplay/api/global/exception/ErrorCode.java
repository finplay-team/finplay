// PRD §5 공통 오류표의 코드·HTTP 상태·기본 메시지를 정의하는 열거형
package com.finplay.api.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	EMAIL_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "이메일 인증번호가 올바르지 않거나 만료되었습니다."),
	OAUTH_AUTHORIZATION_FAILED(HttpStatus.BAD_REQUEST, "OAuth 인가가 취소되었거나 유효하지 않습니다."),
	OAUTH_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "소셜 제공자가 이메일을 제공하지 않았습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	REAUTHENTICATION_FAILED(HttpStatus.FORBIDDEN, "재인증에 실패했습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
	FAVORITE_NOT_FOUND(HttpStatus.NOT_FOUND, "즐겨찾기를 찾을 수 없습니다."),
	WATCHLIST_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "관심목록 항목을 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
	PRACTICE_STEP_LOCKED(HttpStatus.CONFLICT, "선행 실습 단계를 완료해야 합니다."),
	PRACTICE_STAGE_LOCKED(HttpStatus.CONFLICT, "앞 단계를 먼저 마쳐야 합니다."),
	PRACTICE_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 완료한 실습입니다."),
	PRACTICE_EVIDENCE_MISSING(HttpStatus.CONFLICT, "실습 진행에 필요한 증거를 확인할 수 없습니다."),
	PRACTICE_SANDBOX_TIME_EXPIRED(HttpStatus.CONFLICT, "실습 매수 후 5분이 지나 이 시도는 만료됐습니다. 다시 매수해 주세요."),
	INSTRUMENT_NOT_TRADABLE(HttpStatus.CONFLICT, "거래할 수 없는 종목입니다."),
	EMAIL_VERIFICATION_REQUIRED(HttpStatus.CONFLICT, "이메일 인증이 필요합니다."),
	ACCOUNT_LINK_REQUIRED(HttpStatus.CONFLICT, "같은 이메일의 일반 회원이 있어 계정 연결이 필요합니다."),
	SOCIAL_ACCOUNT_ONLY(HttpStatus.CONFLICT, "소셜 로그인 전용 계정입니다. 카카오 또는 네이버 로그인을 이용해 주세요."),
	INSUFFICIENT_CASH(HttpStatus.CONFLICT, "현금 잔고가 부족합니다."),
	TUTORIAL_INSUFFICIENT_CASH(HttpStatus.CONFLICT, "튜토리얼 계좌의 현금 잔고가 부족합니다."),
	INSUFFICIENT_QTY(HttpStatus.CONFLICT, "매도 가능 수량이 부족합니다."),
	MARKET_CLOSED(HttpStatus.CONFLICT, "장이 종료되었습니다."),
	PRICE_UNAVAILABLE(HttpStatus.CONFLICT, "최신 시세를 조회할 수 없습니다."),
	PRACTICE_PRICE_SESSION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "이미 진행 중인 가상 가격 세션이 있습니다."),
	PRACTICE_PRICE_SESSION_CLOSED(HttpStatus.CONFLICT, "이미 종료된 가상 가격 세션입니다."),
	PRACTICE_PRICE_TICK_CONFLICT(HttpStatus.CONFLICT, "요청한 tick이 현재 진행 위치와 일치하지 않습니다."),
	PRACTICE_LIMIT_ORDER_ALREADY_PENDING(HttpStatus.CONFLICT, "이미 대기 중인 교육 지정가 주문이 있습니다."),
	PRACTICE_PRICE_SESSION_MISMATCH(HttpStatus.CONFLICT, "주문의 사용자 또는 종목이 가상 가격 세션과 일치하지 않습니다."),
	EXIT_PLAN_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 손절·익절 예약이 있어 다시 걸 수 없습니다."),
	EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED(HttpStatus.CONFLICT, "샌드박스 종목은 일반 리스크관리 OCO를 지원하지 않습니다."),
	EXIT_PLAN_INVALID_PRICE_RANGE(HttpStatus.CONFLICT, "손절가와 익절가의 범위가 올바르지 않습니다."),
	EXIT_PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "손절·익절 예약을 찾을 수 없습니다."),
	EXIT_PLAN_NOT_PENDING(HttpStatus.CONFLICT, "대기 중인 예약만 취소할 수 있습니다."),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 키의 다른 요청이 이미 처리되었습니다."),
	UNSUPPORTED_ORDER_TYPE(HttpStatus.UNPROCESSABLE_CONTENT, "지원하지 않는 주문 유형입니다."),
	ORDER_ALREADY_FILLED(HttpStatus.CONFLICT, "이미 체결된 주문입니다."),
	ORDER_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 주문입니다."),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
	OAUTH_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "OAuth 공급자 요청을 처리할 수 없습니다."),
	MARKET_DATA_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "시세 데이터 공급자 요청을 처리할 수 없습니다."),
	RANKING_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "랭킹 데이터를 조회할 수 없습니다.");

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
