// ErrorCode를 담아 도메인 서비스가 던지는 비즈니스 런타임 예외
package com.finplay.api.global.exception;

public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getDefaultMessage());
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	// 클라이언트 응답 메시지는 ErrorCode 기본 메시지로 고정하고, 상세 원인은 cause로만 남긴다 — 캐치를 빠뜨린
	// 호출자가 생겨도 GlobalExceptionHandler가 내부 진단 문자열을 그대로 응답에 실어 보내지 않게 하기 위해서다
	// (PR #523 리뷰 [권장 5]).
	public BusinessException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.getDefaultMessage(), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
