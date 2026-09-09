package com.finplay.api.global.exception;

public record ErrorResponse(ErrorBody error) {

	public record ErrorBody(String code, String message, String requestId) {
	}

	public static ErrorResponse of(ErrorCode errorCode, String message, String requestId) {
		return new ErrorResponse(new ErrorBody(errorCode.name(), message, requestId));
	}
}
