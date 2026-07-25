// 오류 응답을 {"error":{code,message,requestId}} 포맷으로 직렬화하는 record
package com.finplay.api.common;

public record ErrorResponse(ErrorBody error) {

	public record ErrorBody(String code, String message, String requestId) {
	}

	public static ErrorResponse of(ErrorCode errorCode, String message, String requestId) {
		return new ErrorResponse(new ErrorBody(errorCode.name(), message, requestId));
	}
}
